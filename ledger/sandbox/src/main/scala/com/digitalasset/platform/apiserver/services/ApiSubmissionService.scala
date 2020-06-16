// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.platform.apiserver.services

import java.time.{Duration, Instant}
import java.util.UUID
import java.util.concurrent.RejectedExecutionException

import akka.stream.Materializer
import com.daml.api.util.TimeProvider
import com.daml.dec.DirectExecutionContext
import com.daml.ledger.api.domain.{LedgerId, Commands => ApiCommands}
import com.daml.ledger.api.messages.command.submission.SubmitRequest
import com.daml.ledger.participant.state.index.v2._
import com.daml.ledger.participant.state.v1
import com.daml.ledger.participant.state.v1.SubmissionResult.{Acknowledged, InternalError, NotSupported, Overloaded}
import com.daml.ledger.participant.state.v1.{Configuration, SeedService, SubmissionResult, WriteService}
import com.daml.lf.crypto
import com.daml.lf.data.Ref.Party
import com.daml.lf.transaction.{Transaction => Tx}
import com.daml.logging.LoggingContext.withEnrichedLoggingContext
import com.daml.logging.{ContextualizedLogger, LoggingContext, ThreadLogger}
import com.daml.metrics.Metrics
import com.daml.platform.api.grpc.GrpcApiService
import com.daml.platform.apiserver.ExecutionContexts
import com.daml.platform.apiserver.execution.{CommandExecutionResult, CommandExecutor}
import com.daml.platform.server.api.services.domain.CommandSubmissionService
import com.daml.platform.server.api.services.grpc.GrpcCommandSubmissionService
import com.daml.platform.server.api.validation.ErrorFactories
import com.daml.platform.services.time.TimeProviderType
import com.daml.platform.store.ErrorCause
import com.daml.timer.Delayed
import io.grpc.Status

import scala.compat.java8.FutureConverters.CompletionStageOps
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.Duration.Inf
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

object ApiSubmissionService {

  def create(
      ledgerId: LedgerId,
      contractStore: ContractStore,
      writeService: WriteService,
      submissionService: IndexSubmissionService,
      partyManagementService: IndexPartyManagementService,
      timeProvider: TimeProvider,
      timeProviderType: TimeProviderType,
      ledgerConfigProvider: LedgerConfigProvider,
      seedService: SeedService,
      commandExecutor: CommandExecutor,
      configuration: ApiSubmissionService.Configuration,
      metrics: Metrics,
      executionContexts: ExecutionContexts,
  )(
      implicit mat: Materializer,
      logCtx: LoggingContext
  ): GrpcCommandSubmissionService with GrpcApiService =
    new GrpcCommandSubmissionService(
      service = new ApiSubmissionService(
        contractStore,
        writeService,
        submissionService,
        partyManagementService,
        timeProvider,
        timeProviderType,
        ledgerConfigProvider,
        seedService,
        commandExecutor,
        configuration,
        metrics,
        executionContexts
      ),
      ledgerId = ledgerId,
      currentLedgerTime = () => timeProvider.getCurrentTime,
      currentUtcTime = () => Instant.now,
      maxDeduplicationTime =
        () => ledgerConfigProvider.latestConfiguration.map(_.maxDeduplicationTime),
      metrics = metrics,
    )

  final case class Configuration(
      implicitPartyAllocation: Boolean,
  )
}

final class ApiSubmissionService private (
    contractStore: ContractStore,
    writeService: WriteService,
    submissionService: IndexSubmissionService,
    partyManagementService: IndexPartyManagementService,
    timeProvider: TimeProvider,
    timeProviderType: TimeProviderType,
    ledgerConfigProvider: LedgerConfigProvider,
    seedService: SeedService,
    commandExecutor: CommandExecutor,
    configuration: ApiSubmissionService.Configuration,
    metrics: Metrics,
    executionContexts: ExecutionContexts,
)(implicit mat: Materializer, logCtx: LoggingContext)
    extends CommandSubmissionService
    with ErrorFactories
    with AutoCloseable {

  private val logger = ContextualizedLogger.get(this.getClass)

  private def deduplicateAndRecordOnLedger(
      seed: crypto.Hash,
      commands: ApiCommands,
      ledgerConfig: Configuration)(implicit logCtx: LoggingContext): Future[Unit] = {
    ThreadLogger.traceThread("ApiSubmissionService.deduplicateAndRecordOnLedger")
    val submittedAt = commands.submittedAt
    val deduplicateUntil = commands.deduplicateUntil

    submissionService
      .deduplicateCommand(commands.commandId, commands.submitter, submittedAt, deduplicateUntil)
      .flatMap {
        case CommandDeduplicationNew =>
          recordOnLedger(seed, commands, ledgerConfig)
            .transform(mapSubmissionResult)(executionContexts.monadicCommandExecutionContext)
            .recoverWith {
              case error =>
                submissionService
                  .stopDeduplicatingCommand(commands.commandId, commands.submitter)
                  .transform(_ => Failure(error))(executionContexts.monadicCommandExecutionContext)
            }(executionContexts.monadicCommandExecutionContext)
        case CommandDeduplicationDuplicate(until) =>
          metrics.daml.commands.deduplicatedCommands.mark()
          val reason =
            s"A command with the same command ID ${commands.commandId} and submitter ${commands.submitter} was submitted before. Deduplication window until $until"
          logger.debug(reason)
          Future.failed(Status.ALREADY_EXISTS.augmentDescription(reason).asRuntimeException)
      }(executionContexts.monadicCommandExecutionContext)
  }

  override def submit(request: SubmitRequest): Future[Unit] = {
    ThreadLogger.traceThread("ApiSubmissionService.submit")
    withEnrichedLoggingContext(
      logging.commandId(request.commands.commandId),
      logging.party(request.commands.submitter)) { implicit logCtx =>
      val commands = request.commands

      logger.trace(s"Received composite commands: $commands")
      logger.debug(s"Received composite command let ${commands.commands.ledgerEffectiveTime}.")

      val promise = Promise[Unit]

      val runnable = new Runnable {
        override def run(): Unit = {
          val resultFuture =
            ledgerConfigProvider.latestConfiguration.fold[Future[Unit]](
              Future.failed(ErrorFactories.missingLedgerConfig())
            )(
              ledgerConfig =>
                deduplicateAndRecordOnLedger(seedService.nextSeed(), commands, ledgerConfig)
                  .andThen(logger.logErrorsOnCall[Unit])(DirectExecutionContext))
          promise.completeWith(resultFuture)
          // Blocking here to not hop out of the thread pool immediately
          val _ = Await.ready(resultFuture, Inf)
        }
      }
      try {
        executionContexts.commandExecution.execute(runnable)
      } catch {
        case _: RejectedExecutionException =>
          logger.error("command queue full. try again later")
          promise.failure(ErrorFactories.resourceExhausted("Command Execution Slot full"))
        case NonFatal(other) =>
          logger.error("other issue", other)
          promise.failure(ErrorFactories.internal(other.getMessage))
      }
      promise.future
    }
  }

  private def mapSubmissionResult(result: Try[SubmissionResult])(
      implicit logCtx: LoggingContext): Try[Unit] = result match {
    case Success(Acknowledged) =>
      logger.debug("Submission of command succeeded")
      Success(())

    case Success(Overloaded) =>
      logger.info("Submission has failed due to backpressure")
      Failure(Status.RESOURCE_EXHAUSTED.asRuntimeException)

    case Success(NotSupported) =>
      logger.warn("Submission of command was not supported")
      Failure(Status.INVALID_ARGUMENT.asRuntimeException)

    case Success(InternalError(reason)) =>
      logger.error(s"Submission of command failed due to an internal error, reason=$reason")
      Failure(Status.INTERNAL.augmentDescription(reason).asRuntimeException)

    case Failure(error) =>
      logger.info(s"Submission of command rejected: ${error.getMessage}")
      Failure(error)
  }

  private def recordOnLedger(
      submissionSeed: crypto.Hash,
      commands: ApiCommands,
      ledgerConfig: Configuration,
  )(implicit logCtx: LoggingContext): Future[SubmissionResult] = {
    ThreadLogger.traceThread("ApiSubmissionService.recordOnLedger")
    implicit val ec: ExecutionContext = executionContexts.monadicCommandExecutionContext
    for {
      res <- commandExecutor.execute(commands, submissionSeed)
      transactionInfo <- res.fold(error => {
        metrics.daml.commands.failedCommandInterpretations.mark()
        Future.failed(grpcError(toStatus(error)))
      }, Future.successful)
      partyAllocationResults <- allocateMissingInformees(transactionInfo.transaction)
      submissionResult <- submitTransaction(transactionInfo, partyAllocationResults, ledgerConfig)
    } yield submissionResult
  }

  private def allocateMissingInformees(
      transaction: Tx.SubmittedTransaction,
  )(implicit ec: ExecutionContext): Future[Seq[SubmissionResult]] = {
    ThreadLogger.traceThread("ApiSubmissionService.allocateMissingInformees")
    if (configuration.implicitPartyAllocation) {
      val parties: Set[Party] = transaction.nodes.values.flatMap(_.informeesOfNode).toSet
      partyManagementService.getParties(parties.toSeq).flatMap { partyDetails =>
        val missingParties = parties -- partyDetails.map(_.party)
        if (missingParties.nonEmpty) {
          logger.info(s"Implicitly allocating the parties: ${missingParties.mkString(", ")}")
          Future.sequence(
            missingParties.toSeq
              .map(name =>
                writeService.allocateParty(
                  hint = Some(name),
                  displayName = Some(name),
                  // TODO: Just like the ApiPartyManagementService, this should do proper validation.
                  submissionId = v1.SubmissionId.assertFromString(UUID.randomUUID().toString),
              ))
              .map(_.toScala))
        } else {
          Future.successful(Seq.empty)
        }
      }
    } else {
      Future.successful(Seq.empty)
    }
  }
}

  private def submitTransaction(
      transactionInfo: CommandExecutionResult,
      partyAllocationResults: Seq[SubmissionResult],
      ledgerConfig: Configuration,
  ): Future[SubmissionResult] = {
    ThreadLogger.traceThread("ApiSubmissionService.submitTransaction")
    partyAllocationResults.find(_ != SubmissionResult.Acknowledged) match {
      case Some(result) =>
        Future.successful(result)
      case None =>
        timeProviderType match {
          case TimeProviderType.WallClock =>
            // Submit transactions such that they arrive at the ledger sequencer exactly when record time equals ledger time.
            // If the ledger time of the transaction is far in the future (farther than the expected latency),
            // the submission to the WriteService is delayed.
            val submitAt = transactionInfo.transactionMeta.ledgerEffectiveTime.toInstant
              .minus(ledgerConfig.timeModel.avgTransactionLatency)
            val submissionDelay = Duration.between(timeProvider.getCurrentTime, submitAt)
            if (submissionDelay.isNegative)
              submitTransaction(transactionInfo)
            else {
              metrics.daml.commands.delayedSubmissions.mark()
              val scalaDelay = scala.concurrent.duration.Duration.fromNanos(submissionDelay.toNanos)
              Delayed.Future.by(scalaDelay)(submitTransaction(transactionInfo))(executionContexts.monadicCommandExecutionContext)
            }
          case TimeProviderType.Static =>
            // In static time mode, record time is always equal to ledger time
            submitTransaction(transactionInfo)
        }
    }
  }

  private def submitTransaction(
      result: CommandExecutionResult,
  ): Future[SubmissionResult] = {
    ThreadLogger.traceThread("ApiSubmissionService.submitTransaction (2)")
    metrics.daml.commands.validSubmissions.mark()
    writeService
      .submitTransaction(result.submitterInfo, result.transactionMeta, result.transaction)
      .toScala
  }

  private def toStatus(errorCause: ErrorCause) =
    errorCause match {
      case e: ErrorCause.DamlLf =>
        Status.INVALID_ARGUMENT.withDescription(e.explain)
      case e: ErrorCause.LedgerTime =>
        Status.ABORTED.withDescription(e.explain)
    }

  override def close(): Unit = ()

}
