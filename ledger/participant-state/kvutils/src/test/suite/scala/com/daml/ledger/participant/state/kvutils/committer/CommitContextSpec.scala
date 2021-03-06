// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.participant.state.kvutils.committer

import com.daml.ledger.participant.state.kvutils.DamlKvutils.{
  DamlPartyAllocation,
  DamlStateKey,
  DamlStateValue
}
import com.daml.ledger.participant.state.kvutils.Err.MissingInputState
import com.daml.ledger.participant.state.kvutils.{DamlStateMapWithFingerprints, TestHelpers}
import com.daml.ledger.participant.state.v1.ParticipantId
import com.daml.lf.data.Time
import org.scalatest.{Matchers, WordSpec}

class CommitContextSpec extends WordSpec with Matchers {
  "get" should {
    "check output first" in {
      val context = newInstance(inputsWithFingerprints = newDamlStateMap(aKey -> anotherValue))
      context.set(aKey, aValue)
      context.get(aKey) shouldBe Some(aValue)
    }

    "return input if key has not been output" in {
      val context = newInstance(inputsWithFingerprints = newDamlStateMap(aKey -> aValue))
      context.get(aKey) shouldBe Some(aValue)
    }

    "record all accessed input keys" in {
      val context = newInstance(
        inputsWithFingerprints = newDamlStateMap(aKey -> aValue, anotherKey -> anotherValue))
      context.get(aKey)
      context.get(anotherKey)

      context.getAccessedInputKeysWithFingerprints shouldBe Set(
        aKey -> aValue.toByteString,
        anotherKey -> anotherValue.toByteString)
    }

    "not record input keys that are not accessed" in {
      val context =
        newInstance(
          inputsWithFingerprints = newDamlStateMap(aKey -> aValue, anotherKey -> anotherValue))
      context.get(aKey)

      context.getAccessedInputKeysWithFingerprints shouldBe Set(aKey -> aValue.toByteString)
    }

    "throw in case key cannot be found" in {
      val context = newInstance()
      assertThrows[MissingInputState](context.get(aKey))
    }
  }

  "set" should {
    "maintain order of keys based on when they were seen first" in {
      val context = newInstance()

      context.set(aKey, aValue)
      context.set(anotherKey, anotherValue)
      context.set(aKey, anotherValue)

      context.getOutputs.map(_._1).toSeq shouldBe Seq(aKey, anotherKey)
    }

    "update value for an already output key" in {
      val context = newInstance()

      context.set(aKey, aValue)
      context.set(aKey, anotherValue)

      context.getOutputs.toSeq shouldBe Seq((aKey, anotherValue))
    }

    "not output a key whose value is identical to its input value" in {
      val context = newInstance(inputsWithFingerprints = newDamlStateMap(aKey -> aValue))
      context.set(aKey, aValue)
      context.getOutputs should have size 0
    }

    "output a key whose value has changed from its input value" in {
      val context = newInstance(inputsWithFingerprints = newDamlStateMap(aKey -> aValue))
      context.set(aKey, anotherValue)
      context.getOutputs.toSeq shouldBe Seq((aKey, anotherValue))
    }

    "output last set value for a key that was also input" in {
      val context = newInstance(inputsWithFingerprints = newDamlStateMap(aKey -> aValue))

      context.set(aKey, anotherValue)
      context.set(aKey, aValue)

      context.getOutputs should have size 0
    }
  }

  "preExecute" should {
    "return false in case record time is set" in {
      val context = newInstance(recordTime = Some(Time.Timestamp.now()))
      context.preExecute shouldBe false
    }

    "return true in case record time is not set" in {
      val context = newInstance(recordTime = None)
      context.preExecute shouldBe true
    }
  }

  private val aKey: DamlStateKey = DamlStateKey.newBuilder.setContractId("contract ID 1").build
  private val anotherKey: DamlStateKey =
    DamlStateKey.newBuilder.setContractId("contract ID 2").build
  private val aValue: DamlStateValue = DamlStateValue.newBuilder
    .setParty(DamlPartyAllocation.newBuilder.setDisplayName("a party name"))
    .build
  private val anotherValue: DamlStateValue = DamlStateValue.newBuilder
    .setParty(DamlPartyAllocation.newBuilder.setDisplayName("another party name"))
    .build

  private class TestCommitContext(
      override val getRecordTime: Option[Time.Timestamp],
      override val inputsWithFingerprints: DamlStateMapWithFingerprints)
      extends CommitContext {
    override def getParticipantId: ParticipantId = TestHelpers.mkParticipantId(1)
  }

  private def newInstance(
      recordTime: Option[Time.Timestamp] = Some(Time.Timestamp.now()),
      inputsWithFingerprints: DamlStateMapWithFingerprints = Map.empty) =
    new TestCommitContext(recordTime, inputsWithFingerprints)

  private def newDamlStateMap(
      keyAndValues: (DamlStateKey, DamlStateValue)*): DamlStateMapWithFingerprints =
    (for ((key, value) <- keyAndValues)
      yield (key, (Some(value), value.toByteString))).toMap
}
