package com.daml.ledger.validator.preexecution

import java.security.MessageDigest
import java.time.Instant

import com.daml.ledger.participant.state.kvutils.DamlKvutils.{
  DamlLogEntryId,
  DamlStateKey,
  DamlStateValue,
  DamlSubmission
}
import com.daml.ledger.participant.state.kvutils.KeyValueCommitting.PreExecutionResult
import com.daml.ledger.participant.state.kvutils.api.LedgerReader
import com.daml.ledger.participant.state.kvutils.{Bytes, Envelope, Fingerprint, KeyValueCommitting}
import com.daml.ledger.participant.state.v1.ParticipantId
import com.daml.ledger.validator.preexecution.PreExecutionCommitResult.ReadSet
import com.daml.ledger.validator.{StateKeySerializationStrategy, ValidationFailed}
import com.daml.logging.{ContextualizedLogger, LoggingContext}
import com.daml.metrics.Metrics
import com.google.protobuf.ByteString

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

sealed case class PreExecutionOutput[WriteSet](
    minRecordTime: Option[Instant],
    maxRecordTime: Option[Instant],
    successWriteSet: WriteSet,
    outOfTimeBoundsWriteSet: WriteSet,
    readSet: ReadSet,
    involvedParticipants: Set[ParticipantId]
)

class PreExecutingSubmissionValidator[WriteSet](
    committer: KeyValueCommitting,
    metrics: Metrics,
    keySerializationStrategy: StateKeySerializationStrategy,
    commitStrategy: PreExecutingCommitStrategy[WriteSet]) {
  private val logger = ContextualizedLogger.get(getClass)

  def validate(
      submissionEnvelope: Bytes,
      correlationId: String,
      submittingParticipantId: ParticipantId,
      ledgerStateReader: DamlLedgerStateReaderWithFingerprints,
  )(implicit executionContext: ExecutionContext): Future[PreExecutionOutput[WriteSet]] =
    LoggingContext.newLoggingContext { implicit logCtx =>
      for {
        decodedSubmission <- decodeSubmission(submissionEnvelope)
        fetchedInputs <- fetchSubmissionInputs(decodedSubmission, ledgerStateReader)
        preExecutionResult <- preExecuteSubmission(
          decodedSubmission,
          submittingParticipantId,
          fetchedInputs)
        logEntryId = bytesToLogEntryId(submissionEnvelope)
        inputState = fetchedInputs.map { case (key, (value, _)) => key -> value }
        generatedWriteSets <- commitStrategy.generateWriteSets(
          submittingParticipantId,
          logEntryId,
          inputState,
          preExecutionResult)
      } yield {
        PreExecutionOutput(
          minRecordTime = preExecutionResult.minimumRecordTime.map(_.toInstant),
          maxRecordTime = preExecutionResult.maximumRecordTime.map(_.toInstant),
          successWriteSet = generatedWriteSets.successWriteSet,
          outOfTimeBoundsWriteSet = generatedWriteSets.outOfTimeBoundsWriteSet,
          readSet = generateReadSet(preExecutionResult.readSet),
          involvedParticipants = generatedWriteSets.involvedParticipants
        )
      }
    }

  private def decodeSubmission(submissionEnvelope: Bytes)(
      implicit logCtx: LoggingContext): Future[DamlSubmission] =
    metrics.daml.kvutils.submission.validator.decode
      .time(() => Envelope.open(submissionEnvelope)) match {
      case Right(Envelope.SubmissionMessage(submission)) =>
        // TODO: Update metrics related to submission size.
        Future.successful(submission)

      case Right(Envelope.SubmissionBatchMessage(batch)) =>
        logger.error("Batched submissions are not supported for pre-execution")
        Future.failed(
          ValidationFailed.ValidationError(
            "Batched submissions are not supported for pre-execution"))

      case Right(other) =>
        Future.failed(
          ValidationFailed.ValidationError(
            s"Unexpected message in envelope: ${other.getClass.getSimpleName}"))

      case Left(error) =>
        Future.failed(ValidationFailed.ValidationError(s"Cannot open envelope: $error"))
    }

  type DamlInputStateWithFingerprints = Map[DamlStateKey, (Option[DamlStateValue], Fingerprint)]

  private def fetchSubmissionInputs(
      submission: DamlSubmission,
      ledgerStateReader: DamlLedgerStateReaderWithFingerprints)(
      implicit executionContext: ExecutionContext): Future[DamlInputStateWithFingerprints] = {
    val inputKeys = submission.getInputDamlStateList.asScala
    ledgerStateReader
      .read(inputKeys)
      .map { values =>
        inputKeys.zip(values).toMap
      }
  }

  private def preExecuteSubmission(
      submission: DamlSubmission,
      submittingParticipantId: ParticipantId,
      inputState: DamlInputStateWithFingerprints)(
      implicit executionContext: ExecutionContext): Future[PreExecutionResult] = Future {
    committer.preExecuteSubmission(
      LedgerReader.DefaultConfiguration,
      submission,
      submittingParticipantId,
      inputState)
  }

  private def generateReadSet(keyToFingerprint: Map[DamlStateKey, Fingerprint]): ReadSet =
    keyToFingerprint
      .map {
        case (damlKey, fingerprint) =>
          keySerializationStrategy.serializeStateKey(damlKey) -> fingerprint
      }
      .toVector
      .sortBy(_._1.asReadOnlyByteBuffer)

  // TODO: Share with BatchedSubmissionValidator.
  private val LogEntryIdPrefix = "0"

  private def bytesToLogEntryId(bytes: ByteString): DamlLogEntryId = {
    val messageDigest = MessageDigest
      .getInstance("SHA-256")
    messageDigest.update(bytes.asReadOnlyByteBuffer())
    val hash = messageDigest
      .digest()
      .map("%02x" format _)
      .mkString
    val prefixedHash = ByteString.copyFromUtf8(LogEntryIdPrefix + hash)
    DamlLogEntryId.newBuilder
      .setEntryId(prefixedHash)
      .build
  }
}
