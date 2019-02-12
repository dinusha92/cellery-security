/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

package io.cellery.security.cell.sts.server.core;

import io.cellery.security.cell.sts.server.core.context.store.UserContextStore;
import io.cellery.security.cell.sts.server.core.context.store.UserContextStoreImpl;
import io.cellery.security.cell.sts.server.core.model.config.CellStsConfiguration;
import io.cellery.security.cell.sts.server.core.service.CelleryCellInboundInterceptorService;
import io.cellery.security.cell.sts.server.core.service.CelleryCellOutboundInterceptorService;
import io.cellery.security.cell.sts.server.core.service.CelleryCellSTSException;
import io.cellery.security.cell.sts.server.core.service.CelleryCellStsService;
import io.cellery.security.cell.sts.server.jwks.JWKSServer;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.apache.commons.lang.StringUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Intercepts outbound calls from micro service proxy.
 */
public class CelleryCellSTSServer {

    private static final String CELL_NAME_ENV_VARIABLE = "CELL_NAME";
    private static final String STS_CONFIG_PATH_ENV_VARIABLE = "CONF_PATH";
    private static final String CONFIG_FILE_PATH = "/etc/config/sts.json";

    private static final String CONFIG_STS_ENDPOINT = "endpoint";
    private static final String CONFIG_AUTH_USERNAME = "username";
    private static final String CONFIG_AUTH_PASSWORD = "password";
    private static final String CONFIG_GLOBAL_JWKS = "globalJWKS";
    private static final String CONFIG_SIGNATURE_VALIDATION_ENABLED = "enableSignatureValidation";
    private static final String CONFIG_ISSUER_VALIDATION_ENABLED = "enableIssuerValidation";
    private static final String CONFIG_AUDIENCE_VALIDATION_ENABLED = "enableAudienceValidation";

    private static final Logger log = LoggerFactory.getLogger(CelleryCellSTSServer.class);
    private final int inboundListeningPort;
    private final Server inboundListener;

    private final int outboundListeningPort;
    private final Server outboundListener;

    private CelleryCellSTSServer(int inboundListeningPort, int outboundListeningPort) throws CelleryCellSTSException {

        CellStsConfiguration stsConfig = buildCellStsConfiguration();
        log.info("Cell STS configuration:\n" + stsConfig);

        UserContextStore contextStore = new UserContextStoreImpl();
        UserContextStore localContextStore = new UserContextStoreImpl();

        CelleryCellStsService cellStsService = new CelleryCellStsService(stsConfig, contextStore, localContextStore);

        this.inboundListeningPort = inboundListeningPort;
        inboundListener = ServerBuilder.forPort(inboundListeningPort)
                .addService(new CelleryCellInboundInterceptorService(cellStsService))
                .build();

        this.outboundListeningPort = outboundListeningPort;
        outboundListener = ServerBuilder.forPort(outboundListeningPort)
                .addService(new CelleryCellOutboundInterceptorService(cellStsService))
                .build();
    }

    private CellStsConfiguration buildCellStsConfiguration() throws CelleryCellSTSException {

        try {
            String configFilePath = getConfigFilePath();
            String content = new String(Files.readAllBytes(Paths.get(configFilePath)));
            JSONObject config = (JSONObject) new JSONParser().parse(content);

            return new CellStsConfiguration()
                    .setCellName(getMyCellName())
                    .setStsEndpoint((String) config.get(CONFIG_STS_ENDPOINT))
                    .setUsername((String) config.get(CONFIG_AUTH_USERNAME))
                    .setPassword((String) config.get(CONFIG_AUTH_PASSWORD))
                    .setGlobalJWKEndpoint((String) config.get(CONFIG_GLOBAL_JWKS))
                    .setSignatureValidationEnabled(Boolean.parseBoolean(String.valueOf(config.get
                            (CONFIG_SIGNATURE_VALIDATION_ENABLED))))
                    .setAudienceValidationEnabled(Boolean.parseBoolean(String.valueOf(config.get
                            (CONFIG_AUDIENCE_VALIDATION_ENABLED))))
                    .setIssuerValidationEnabled(Boolean.parseBoolean(String.valueOf(config.get
                            (CONFIG_ISSUER_VALIDATION_ENABLED))));
        } catch (ParseException | IOException e) {
            throw new CelleryCellSTSException("Error while setting up STS configurations", e);
        }
    }

    private String getConfigFilePath() {

        String configPath = System.getenv(STS_CONFIG_PATH_ENV_VARIABLE);
        return StringUtils.isNotBlank(configPath) ? configPath : CONFIG_FILE_PATH;
    }

    private String getMyCellName() throws CelleryCellSTSException {
        // For now we pick the cell name from the environment variable.
        String cellName = System.getenv(CELL_NAME_ENV_VARIABLE);
        if (StringUtils.isBlank(cellName)) {
            throw new CelleryCellSTSException("Environment variable '" + CELL_NAME_ENV_VARIABLE + "' is empty.");
        }
        return cellName;
    }

    /**
     * Start serving requests.
     */
    private void start() throws IOException {

        inboundListener.start();
        outboundListener.start();
        log.info("Cellery STS gRPC Server started, listening for inbound traffic on " + inboundListeningPort);
        log.info("Cellery STS gRPC Server started, listening for outbound traffic on " + outboundListeningPort);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Use stderr here since the logger may has been reset by its JVM shutdown hook.
            System.err.println("Shutting down Cellery Cell STS since JVM is shutting down.");
            CelleryCellSTSServer.this.stop();
            System.err.println("Cellery Cell STS shut down.");
        }));
    }

    /**
     * Stop serving requests and shutdown resources.
     */
    private void stop() {

        if (inboundListener != null) {
            inboundListener.shutdown();
        }

        if (outboundListener != null) {
            outboundListener.shutdown();
        }
    }

    /**
     * Await termination on the main thread since the grpc library uses daemon threads.
     */
    private void blockUntilShutdown() throws InterruptedException {

        if (inboundListener != null) {
            inboundListener.awaitTermination();
        }

        if (outboundListener != null) {
            outboundListener.awaitTermination();
        }
    }

    public static void main(String[] args) {

        CelleryCellSTSServer server;
        int inboundListeningPort = getPortFromEnvVariable("inboundPort", 8080);
        int outboundListeningPort = getPortFromEnvVariable("outboundPort", 8081);;
        int jwksEndpointPort = getPortFromEnvVariable("jwksPort", 8090);

        try {
            server = new CelleryCellSTSServer(inboundListeningPort, outboundListeningPort);
            server.start();
            JWKSServer jwksServer = new JWKSServer(jwksEndpointPort);
            jwksServer.startServer();
            server.blockUntilShutdown();
        } catch (Exception e) {
            log.error("Error while starting up the Cell STS.", e);
            // To make the pod go to CrashLoopBackOff state if we encounter any error while starting up
            System.exit(1);
        }
    }

    private static int getPortFromEnvVariable(String name, int defaultPort) {

        if (StringUtils.isNotEmpty(System.getenv(name))) {
            defaultPort = Integer.parseInt(System.getenv(name));
        }
        log.info("Port for {} : {}", name, defaultPort);
        return defaultPort;
    }

}
