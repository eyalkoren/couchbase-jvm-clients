/*
 * Copyright (c) 2020 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.couchbase;

import com.couchbase.client.core.io.CollectionIdentifier;
import com.couchbase.client.core.logging.LogRedaction;
import com.couchbase.client.core.logging.RedactionLevel;
import com.couchbase.client.core.transaction.cleanup.TransactionsCleaner;
import com.couchbase.client.core.transaction.cleanup.ClientRecord;
import com.couchbase.client.core.transaction.cleanup.ClientRecordDetails;
import com.couchbase.client.core.transaction.components.ActiveTransactionRecordEntry;
import com.couchbase.client.core.transaction.components.ActiveTransactionRecord;
import com.couchbase.client.core.transaction.config.CoreMergedTransactionConfig;
import com.couchbase.client.core.transaction.forwards.Extension;
import com.couchbase.client.core.transaction.forwards.Supported;
import com.couchbase.client.core.cnc.events.transaction.TransactionCleanupAttemptEvent;
import com.couchbase.client.core.transaction.log.CoreTransactionLogger;
import com.couchbase.client.java.transactions.config.TransactionsConfig;
import com.couchbase.grpc.protocol.API;
import com.couchbase.grpc.protocol.CleanupSet;
import com.couchbase.grpc.protocol.CleanupSetFetchRequest;
import com.couchbase.grpc.protocol.CleanupSetFetchResponse;
import com.couchbase.grpc.protocol.ClientRecordProcessRequest;
import com.couchbase.grpc.protocol.ClientRecordProcessResponse;
import com.couchbase.grpc.protocol.ClusterConnectionCloseRequest;
import com.couchbase.grpc.protocol.ClusterConnectionCloseResponse;
import com.couchbase.grpc.protocol.ClusterConnectionCreateRequest;
import com.couchbase.grpc.protocol.ClusterConnectionCreateResponse;
import com.couchbase.grpc.protocol.Collection;
import com.couchbase.grpc.protocol.DisconnectConnectionsRequest;
import com.couchbase.grpc.protocol.DisconnectConnectionsResponse;
import com.couchbase.grpc.protocol.EchoRequest;
import com.couchbase.grpc.protocol.EchoResponse;
import com.couchbase.grpc.protocol.PerformerCaps;
import com.couchbase.grpc.protocol.PerformerCapsFetchRequest;
import com.couchbase.grpc.protocol.PerformerCapsFetchResponse;
import com.couchbase.grpc.protocol.PerformerTransactionServiceGrpc.PerformerTransactionServiceImplBase;
import com.couchbase.grpc.protocol.TransactionCleanupAttempt;
import com.couchbase.grpc.protocol.TransactionCleanupRequest;
import com.couchbase.grpc.protocol.TransactionCreateRequest;
import com.couchbase.grpc.protocol.TransactionResult;
import com.couchbase.grpc.protocol.TransactionSingleQueryRequest;
import com.couchbase.grpc.protocol.TransactionSingleQueryResponse;
import com.couchbase.grpc.protocol.TransactionStreamDriverToPerformer;
import com.couchbase.grpc.protocol.TransactionStreamPerformerToDriver;
import com.couchbase.transactions.SingleQueryTransactionExecutor;
import com.couchbase.twoway.TwoWayTransactionBlocking;
import com.couchbase.twoway.TwoWayTransactionMarshaller;
import com.couchbase.twoway.TwoWayTransactionReactive;
import com.couchbase.utils.ClusterConnection;
import com.couchbase.utils.HooksUtil;
import com.couchbase.utils.OptionsUtil;
import com.couchbase.utils.ResultsUtil;
import com.couchbase.utils.VersionUtil;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Hooks;
import reactor.tools.agent.ReactorDebugAgent;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.couchbase.client.core.io.CollectionIdentifier.DEFAULT_COLLECTION;
import static com.couchbase.client.core.io.CollectionIdentifier.DEFAULT_SCOPE;

public class PerformerTransactionService extends PerformerTransactionServiceImplBase {
    private static final Logger logger = LoggerFactory.getLogger(PerformerTransactionService.class);
    private static final ConcurrentHashMap<String, ClusterConnection> clusterConnections = new ConcurrentHashMap<String, ClusterConnection>();

    // Allows capturing various errors so we can notify the driver of problems.
    public static AtomicReference<String> globalError = new AtomicReference<>();

    public void performerCapsFetch(PerformerCapsFetchRequest request,
                                 StreamObserver<PerformerCapsFetchResponse> responseObserver) {
        try {
            var response = PerformerCapsFetchResponse.newBuilder();

            response.setPerformerLibraryVersion(VersionUtil.introspectSDKVersion());

            for (Extension ext : Extension.SUPPORTED) {
                try {
                    PerformerCaps pc = PerformerCaps.valueOf(ext.name());
                    response.addPerformerCaps(pc);
                } catch (IllegalArgumentException err) {
                    // FIT and Java have used slightly different names for this
                    if (ext.name().equals("EXT_CUSTOM_METADATA")) {
                        response.addPerformerCaps(PerformerCaps.EXT_CUSTOM_METADATA_COLLECTION);
                    } else {
                        logger.warn("Could not find FIT extension for " + ext.name());
                    }
                }
            }

            var supported = new Supported();
            var protocolVersion = supported.protocolMajor + "." + supported.protocolMinor;

            response.setProtocolVersion(protocolVersion);
            response.addSupportedApis(API.DEFAULT);
            response.addSupportedApis(API.ASYNC);
            response.setPerformerUserAgent("java-sdk");

            logger.info("Performer implements protocol {} with caps {}",
                    protocolVersion, response.getPerformerCapsList());

            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        } catch (RuntimeException err) {
            logger.error("Operation failed during performerCapsFetch due to : " + err);
            responseObserver.onError(Status.ABORTED.withDescription(err.toString()).asException());
        }
    }

    @Override
    public void clusterConnectionCreate(ClusterConnectionCreateRequest request,
                                        StreamObserver<ClusterConnectionCreateResponse> responseObserver) {
        try {
            var clusterConnectionId = request.getClusterConnectionId();
            // Need this callback as we have to configure hooks to do something with a Cluster that we haven't created yet.
            Supplier<ClusterConnection> getCluster = () -> clusterConnections.get(clusterConnectionId);
            var clusterEnvironment = OptionsUtil.convertClusterConfig(request, getCluster);

            var connection = new ClusterConnection(request.getClusterHostname(),
                    request.getClusterUsername(),
                    request.getClusterPassword(),
                    Optional.empty(), clusterEnvironment);
            clusterConnections.put(clusterConnectionId, connection);
            logger.info("Created cluster connection {} for user {}, now have {}",
                    clusterConnectionId, request.getClusterUsername(), clusterConnections.size());

            responseObserver.onNext(ClusterConnectionCreateResponse.newBuilder()
                    .setClusterConnectionCount(clusterConnections.size())
                    .build());
            responseObserver.onCompleted();
        } catch (RuntimeException err) {
            logger.error("Operation failed during clusterConnectionCreate due to : " + err);
            responseObserver.onError(Status.ABORTED.withDescription(err.toString()).asException());
        }
    }

    public void clusterConnectionClose(ClusterConnectionCloseRequest request,
                                       StreamObserver<ClusterConnectionCloseResponse> responseObserver) {
        var cc = clusterConnections.get(request.getClusterConnectionId());
        cc.close();
        clusterConnections.remove(request.getClusterConnectionId());
        responseObserver.onNext(ClusterConnectionCloseResponse.newBuilder()
                .setClusterConnectionCount(clusterConnections.size())
                .build());
        responseObserver.onCompleted();
    }

    @Override
    public void transactionCreate(TransactionCreateRequest request,
                                  StreamObserver<TransactionResult> responseObserver) {
        try {
            ClusterConnection connection = getClusterConnection(request.getClusterConnectionId());

            logger.info("Starting transaction on cluster connection {} created for user {}",
                    request.getClusterConnectionId(), connection.username);

            TransactionResult response;
            if (request.getApi() == API.DEFAULT) {
                response = TwoWayTransactionBlocking.run(connection, request);
            }
            else {
                response = TwoWayTransactionReactive.run(connection, request);
            }

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (RuntimeException err) {
            logger.error("Operation failed during transactionCreate due to :  " + err);
            err.printStackTrace();
            responseObserver.onError(Status.ABORTED.withDescription(err.toString()).asException());
        }
    }

    @Override
    public  void echo(EchoRequest request , StreamObserver<EchoResponse> responseObserver){
        try {
            logger.info("================ {} : {} ================ ", request.getTestName(), request.getMessage());
            responseObserver.onNext(EchoResponse.newBuilder().build());
            responseObserver.onCompleted();
        } catch (RuntimeException err) {
            logger.error("Echo of Test {} for message {} failed : {} " +request.getTestName(),request.getMessage(), err);
            responseObserver.onError(Status.ABORTED.withDescription(err.toString()).asException());
        }
    }

    @Override
    public void disconnectConnections(DisconnectConnectionsRequest request, StreamObserver<DisconnectConnectionsResponse> responseObserver) {
        try {
            logger.info("Closing all {} connections from performer to cluster", clusterConnections.size());

            clusterConnections.forEach((key, value) -> value.close());
            clusterConnections.clear();

            responseObserver.onNext(DisconnectConnectionsResponse.newBuilder().build());
            responseObserver.onCompleted();
        } catch (RuntimeException err) {
            logger.error("Operation failed while closing cluster connections : " + err);
            responseObserver.onError(Status.ABORTED.withDescription(err.toString()).asException());
        }
    }

    @Override
    public StreamObserver<TransactionStreamDriverToPerformer> transactionStream(
            StreamObserver<TransactionStreamPerformerToDriver> toTest) {
        var marshaller = new TwoWayTransactionMarshaller(clusterConnections);

        return marshaller.run(toTest);
    }

    private static CollectionIdentifier collectionIdentifierFor(com.couchbase.grpc.protocol.DocId doc) {
        return new CollectionIdentifier(doc.getBucketName(), Optional.of(doc.getScopeName()), Optional.of(doc.getCollectionName()));
    }

    @Override
    public void transactionCleanup(TransactionCleanupRequest request,
                                   StreamObserver<TransactionCleanupAttempt> responseObserver) {
        try {
            logger.info("Starting transaction cleanup attempt");
            // Only the KV timeout is used from this
            var config = TransactionsConfig.builder().build();
            var connection = getClusterConnection(request.getClusterConnectionId());
            var collection = collectionIdentifierFor(request.getAtr());
            connection.waitUntilReady(collection);
            var cleanupHooks = HooksUtil.configureCleanupHooks(request.getHookList(), () -> connection);
            var cleaner = new TransactionsCleaner(connection.core(), cleanupHooks);
            var logger = new CoreTransactionLogger(null, "");
            var merged = new CoreMergedTransactionConfig(config);

            Optional<ActiveTransactionRecordEntry> atrEntry = ActiveTransactionRecord.findEntryForTransaction(connection.core(),
                            collection,
                            request.getAtr().getDocId(),
                            request.getAttemptId(),
                            merged,
                            null,
                            logger)
                    .block();

            TransactionCleanupAttempt response;
            TransactionCleanupAttemptEvent result = null;

            if (atrEntry.isPresent()) {
                result = cleaner.cleanupATREntry(collection,
                        request.getAtrId(),
                        request.getAttemptId(),
                        atrEntry.get(),
                        false)
                        .block();
            }

            if (result != null) {
                response = ResultsUtil.mapCleanupAttempt(result, atrEntry);
            }
            else {
                // Can happen if 2+ cleanups are being executed concurrently
                response = TransactionCleanupAttempt.newBuilder()
                        .setSuccess(false)
                        .setAtr(request.getAtr())
                        .setAttemptId(request.getAttemptId())
                        .addLogs("Failed at performer to get ATR entry before running cleanupATREntry")
                        .build();
            }

            logger.info("Finished transaction cleanup attempt, success={}", response.getSuccess());

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (RuntimeException err) {
            logger.error("Operation failed during transactionCleanup due to : " + err);
            responseObserver.onError(Status.ABORTED.withDescription(err.toString()).asException());
        }
    }

    @Override
    public void clientRecordProcess(ClientRecordProcessRequest request,
                                    StreamObserver<ClientRecordProcessResponse> responseObserver) {
        try {
            logger.info("Starting client record process attempt");

            var config = TransactionsConfig.builder().build();
            ClusterConnection connection = getClusterConnection(request.getClusterConnectionId());

            var collection = new CollectionIdentifier(request.getBucketName(),
                    Optional.of(request.getScopeName()),
                    Optional.of(request.getCollectionName()));

            connection.waitUntilReady(collection);

            ClientRecord cr = HooksUtil.configureClientRecordHooks(request.getHookList(), connection);

            ClientRecordProcessResponse.Builder response = ClientRecordProcessResponse.newBuilder();

            try {
                ClientRecordDetails result = cr.processClient(request.getClientUUID(),
                                collection,
                                config,
                                null)
                        .block();

                response.setSuccess(true)
                        .setNumActiveClients(result.numActiveClients())
                        .setIndexOfThisClient(result.indexOfThisClient())
                        .addAllExpiredClientIds(result.expiredClientIds())
                        .setNumExistingClients(result.numExistingClients())
                        .setNumExpiredClients(result.numExpiredClients())
                        .setOverrideActive(result.overrideActive())
                        .setOverrideEnabled(result.overrideEnabled())
                        .setOverrideExpires(result.overrideExpires())
                        .setCasNowNanos(result.casNow())
                        .setClientUUID(request.getClientUUID())
                        .build();
            }
            catch (RuntimeException err) {
                response.setSuccess(false);
            }

            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        } catch (RuntimeException err) {
            logger.error("Operation failed during clientRecordProcess due to : " + err);
            responseObserver.onError(Status.ABORTED.withDescription(err.toString()).asException());
        }
    }

    @Override
    public void transactionSingleQuery(TransactionSingleQueryRequest request,
                                       StreamObserver<TransactionSingleQueryResponse> responseObserver) {
        try {
            var connection = getClusterConnection(request.getClusterConnectionId());

            logger.info("Performing single query transaction on cluster connection {} (user {})",
                    request.getClusterConnectionId(),
                    connection.username);

            TransactionSingleQueryResponse ret = SingleQueryTransactionExecutor.execute(request, connection);

            responseObserver.onNext(ret);
            responseObserver.onCompleted();
        } catch (Throwable err) {
            logger.error("Operation failed during transactionSingleQuery due to : " + err.toString());
            responseObserver.onError(Status.ABORTED.withDescription(err.toString()).asException());
        }
    }

    public void cleanupSetFetch(CleanupSetFetchRequest request, StreamObserver<CleanupSetFetchResponse> responseObserver) {
        try {
            var connection = getClusterConnection(request.getClusterConnectionId());

            var cleanupSet = connection.core().transactionsCleanup().cleanupSet().stream()
                    .map(cs -> Collection.newBuilder()
                            .setBucketName(cs.bucket())
                            .setScopeName(cs.scope().orElse(DEFAULT_SCOPE))
                            .setCollectionName(cs.collection().orElse(DEFAULT_COLLECTION))
                            .build())
                    .collect(Collectors.toList());

            responseObserver.onNext(CleanupSetFetchResponse.newBuilder()
                            .setCleanupSet(CleanupSet.newBuilder()
                                    .addAllCleanupSet(cleanupSet))
                    .build());
            responseObserver.onCompleted();
        } catch (Throwable err) {
            logger.error("Operation failed during cleanupSetFetch due to {}", err.toString());
            responseObserver.onError(Status.ABORTED.withDescription(err.toString()).asException());
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        int port = 8060;

        // Better reactor stack traces for low cost
        ReactorDebugAgent.init();

        // Control ultra-verbose logging
        System.setProperty("com.couchbase.transactions.debug.lock", "true");
        System.setProperty("com.couchbase.transactions.debug.monoBridge", "false");

        // Setup global error handlers
        Hooks.onErrorDropped(err -> {
            // Temporarily swallowing CompletionException, as they are known error TXNJ-457 and are not adding signal
            if (!(err instanceof CompletionException)) {
                globalError.set("Hook dropped (raised async so could have been in an earlier test): " + err + " cause: " + (err.getCause() != null ? err.getCause().getMessage() : "-"));
            }
        });

        // Blockhound is disabled as it causes an immediate runtime error on Jenkins
//        BlockHound
//                .builder()
//                .blockingMethodCallback(blockingMethod -> {
//                    globalError.set("Blocking method detected: " + blockingMethod);
//                })
//                .install();

        //Need to send parameters in format : port=8060 version=1.1.0 loglevel=all:Info
        for(String parameter : args) {
            switch (parameter.split("=")[0]) {
                case "port":
                    port= Integer.parseInt(parameter.split("=")[1]);
                    break;
                default:
                    logger.warn("Undefined input: {}. Ignoring it",parameter);
            }
        }

        // Force that log redaction has been enabled
        LogRedaction.setRedactionLevel(RedactionLevel.PARTIAL);

        Server server = ServerBuilder.forPort(port)
                .addService(new PerformerTransactionService())
                .build();
        server.start();
        logger.info("Server Started at {}", server.getPort());
        server.awaitTermination();
    }


    public static ClusterConnection getClusterConnection(@Nullable String clusterConnectionId) {
        if (clusterConnections.size() > 2) {
            // Fine to have a default and a per-test connection open, any more suggests a leak
            logger.info("Dumping {} cluster connections for resource leak troubleshooting:", clusterConnections.size());
            clusterConnections.forEach((key, value) -> logger.info("Cluster connection {} {}", key, value.username));
        }

        return clusterConnections.get(clusterConnectionId);
    }
}
