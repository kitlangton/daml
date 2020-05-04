// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.platform.store.dao

import java.util.UUID

import com.codahale.metrics.MetricRegistry
import com.daml.ledger.participant.state.v1.AbsoluteContractInst
import com.daml.lf.value.Value.AbsoluteContractId
import com.daml.logging.LoggingContext
import com.daml.platform.configuration.ServerRole
import com.daml.resources.ResourceOwner
import org.scalatest.{AsyncFlatSpec, LoneElement, Matchers}

private[dao] trait JdbcLedgerDaoPostCommitValidationSpec extends LoneElement {
  this: AsyncFlatSpec with Matchers with JdbcLedgerDaoSuite =>

  override protected def daoOwner(implicit logCtx: LoggingContext): ResourceOwner[LedgerDao] =
    JdbcLedgerDao
      .validatingWriteOwner(
        serverRole = ServerRole.Testing(getClass),
        jdbcUrl = jdbcUrl,
        eventsPageSize = 100,
        metrics = new MetricRegistry,
      )

  private val ok = io.grpc.Status.Code.OK.value()
  private val invalid = io.grpc.Status.Code.INVALID_ARGUMENT.value()

  behavior of "JdbcLedgerDao (post-commit validation)"

  it should "refuse to serialize duplicate contract keys" in {
    val keyValue = s"duplicate-key"

    // Scenario: Two concurrent commands create the same contract key.
    // At command interpretation time, the keys do not exist yet.
    // At serialization time, the ledger should refuse to serialize one of them.
    for {
      from <- ledgerDao.lookupLedgerEnd()
      original @ (_, originalAttempt) = txCreateContractWithKey(alice, keyValue)
      duplicate @ (_, duplicateAttempt) = txCreateContractWithKey(alice, keyValue)
      _ <- store(original)
      _ <- store(duplicate)
      to <- ledgerDao.lookupLedgerEnd()
      completions <- getCompletions(from, to, defaultAppId, Set(alice))
    } yield {
      completions should contain allOf (
        originalAttempt.commandId.get -> ok,
        duplicateAttempt.commandId.get -> invalid,
      )
    }
  }

  it should "refuse to serialize invalid negative lookupByKey" in {
    val keyValue = s"no-invalid-negative-lookup"

    // Scenario: Two concurrent commands: one create and one lookupByKey.
    // At command interpretation time, the lookupByKey does not find any contract.
    // At serialization time, it should be rejected because now the key is there.
    for {
      from <- ledgerDao.lookupLedgerEnd()
      (_, create) <- store(txCreateContractWithKey(alice, keyValue))
      (_, lookup) <- store(txLookupByKey(alice, keyValue, None))
      to <- ledgerDao.lookupLedgerEnd()
      completions <- getCompletions(from, to, defaultAppId, Set(alice))
    } yield {
      completions should contain allOf (
        create.commandId.get -> ok,
        lookup.commandId.get -> invalid,
      )
    }
  }

  it should "refuse to serialize invalid positive lookupByKey" in {
    val keyValue = s"no-invalid-positive-lookup"

    // Scenario: Two concurrent commands: one exercise and one lookupByKey.
    // At command interpretation time, the lookupByKey finds a contract.
    // At serialization time, it should be rejected because now the contract was archived.
    for {
      from <- ledgerDao.lookupLedgerEnd()
      (_, create) <- store(txCreateContractWithKey(alice, keyValue))
      createdContractId = nonTransient(create).loneElement
      (_, archive) <- store(txArchiveContract(alice, createdContractId -> Some(keyValue)))
      (_, lookup) <- store(txLookupByKey(alice, keyValue, Some(createdContractId)))
      to <- ledgerDao.lookupLedgerEnd()
      completions <- getCompletions(from, to, defaultAppId, Set(alice))
    } yield {
      completions should contain allOf (
        create.commandId.get -> ok,
        archive.commandId.get -> ok,
        lookup.commandId.get -> invalid,
      )
    }
  }

  it should "refuse to serialize invalid fetch" in {
    val keyValue = s"no-invalid-fetch"

    // Scenario: Two concurrent commands: one exercise and one fetch.
    // At command interpretation time, the fetch finds a contract.
    // At serialization time, it should be rejected because now the contract was archived.
    for {
      from <- ledgerDao.lookupLedgerEnd()
      (_, create) <- store(txCreateContractWithKey(alice, keyValue))
      createdContractId = nonTransient(create).loneElement
      (_, archive) <- store(txArchiveContract(alice, createdContractId -> Some(keyValue)))
      (_, fetch) <- store(txFetch(alice, createdContractId))
      to <- ledgerDao.lookupLedgerEnd()
      completions <- getCompletions(from, to, defaultAppId, Set(alice))
    } yield {
      completions should contain allOf (
        create.commandId.get -> ok,
        archive.commandId.get -> ok,
        fetch.commandId.get -> invalid,
      )
    }
  }

  it should "be able to use divulged contract in later transaction" in {

    val divulgedContractId =
      AbsoluteContractId.assertFromString(s"#${UUID.randomUUID}")
    val divulgedContracts =
      Map((divulgedContractId, someContractInstance: AbsoluteContractInst) -> Set(alice))

    for {
      from <- ledgerDao.lookupLedgerEnd()
      (_, fetch1) <- store(txFetch(alice, divulgedContractId))
      (_, divulgence) <- store(divulgedContracts, emptyTransaction(alice))
      (_, fetch2) <- store(txFetch(alice, divulgedContractId))
      to <- ledgerDao.lookupLedgerEnd()
      completions <- getCompletions(from, to, defaultAppId, Set(alice))
    } yield {
      completions should contain allOf (
        fetch1.commandId.get -> invalid,
        divulgence.commandId.get -> ok,
        fetch2.commandId.get -> ok,
      )
    }
  }

}
