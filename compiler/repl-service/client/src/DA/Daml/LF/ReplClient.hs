-- Copyright (c) 2020 The DAML Authors. All rights reserved.
-- SPDX-License-Identifier: Apache-2.0

{-# LANGUAGE DataKinds #-}
{-# LANGUAGE GADTs #-}
{-# LANGUAGE MultiWayIf #-}
module DA.Daml.LF.ReplClient
  ( Options(..)
  , Handle
  , withReplClient
  , loadPackage
  , runScript
  , BackendError
  ) where

import Control.Concurrent
import qualified DA.Daml.LF.Ast as LF
import qualified DA.Daml.LF.Proto3.EncodeV1 as EncodeV1
import DA.PortFile
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BSL
import qualified Data.Text.Lazy as TL
import Network.GRPC.HighLevel.Client (ClientError, ClientRequest(..), ClientResult(..), GRPCMethodType(..))
import Network.GRPC.HighLevel.Generated (withGRPCClient)
import Network.GRPC.LowLevel (ClientConfig(..), Host(..), Port(..), StatusCode(..))
import qualified Proto3.Suite as Proto
import qualified ReplService as Grpc
import System.Environment
import System.FilePath
import System.IO.Extra (withTempFile)
import System.Process

data Options = Options
  { optServerJar :: FilePath
  , optLedgerHost :: String
  , optLedgerPort :: String
  }

data Handle = Handle
  { hClient :: Grpc.ReplService ClientRequest ClientResult
  , hOptions :: Options
  }
data BackendError
  = BErrorClient ClientError
  | BErrorFail StatusCode
  deriving Show

-- | Return the 'CreateProcess' for running java.
-- Uses 'java' from JAVA_HOME if set, otherwise calls java via
-- /usr/bin/env. This is needed when running under "bazel run" where
-- JAVA_HOME is correctly set, but 'java' is not in PATH.
javaProc :: [String] -> IO CreateProcess
javaProc args =
  lookupEnv "JAVA_HOME" >>= return . \case
    Nothing ->
      proc "java" args
    Just javaHome ->
      let javaExe = javaHome </> "bin" </> "java"
      in proc javaExe args

withReplClient :: Options -> (Handle -> IO a) -> IO a
withReplClient opts@Options{..} f = withTempFile $ \portFile -> do
    replServer <- javaProc ["-jar", optServerJar, portFile, optLedgerHost, optLedgerPort]
    withCreateProcess replServer $ \_ _ _ _ph -> do
      port <- readPortFile maxRetries portFile
      let grpcConfig = ClientConfig (Host "127.0.0.1") (Port port) [] Nothing Nothing
      threadDelay 1000000
      withGRPCClient grpcConfig $ \client -> do
          replClient <- Grpc.replServiceClient client
          f Handle
              { hClient = replClient
              , hOptions = opts
              }

loadPackage :: Handle -> BS.ByteString -> IO (Either BackendError ())
loadPackage Handle{..} package = do
    r <- performRequest
        (Grpc.replServiceLoadPackage hClient)
        (Grpc.LoadPackageRequest package)
    pure (() <$ r)

runScript :: Handle -> LF.Version -> LF.Module -> IO (Either BackendError ())
runScript Handle{..} version m = do
    r <- performRequest
        (Grpc.replServiceRunScript hClient)
        (Grpc.RunScriptRequest bytes (TL.pack $ LF.renderMinorVersion (LF.versionMinor version)))
    pure (() <$ r)
    where bytes = BSL.toStrict (Proto.toLazyByteString (EncodeV1.encodeScenarioModule version m))

performRequest
  :: (ClientRequest 'Normal payload response -> IO (ClientResult 'Normal response))
  -> payload
  -> IO (Either BackendError response)
performRequest method payload = do
  method (ClientNormalRequest payload 30 mempty) >>= \case
    ClientNormalResponse resp _ _ StatusOk _ -> return (Right resp)
    ClientNormalResponse _ _ _ status _ -> return (Left $ BErrorFail status)
    ClientErrorResponse err -> return (Left $ BErrorClient err)
