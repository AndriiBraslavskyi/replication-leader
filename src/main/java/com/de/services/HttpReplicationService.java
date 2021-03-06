package com.de.services;

import com.de.exceptions.DeadNodeException;
import com.de.exceptions.FailedConnectionException;
import com.de.exceptions.InternalServerException;
import com.de.exceptions.InvalidRequestException;
import com.de.model.Message;
import com.de.repositories.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

public class HttpReplicationService implements ReplicationService {

    private final static Logger logger = LoggerFactory.getLogger(ReplicationService.class);
    private final static String REPLICATION_PATH = "/messages";
    private final static String LOCAL_ADDRESS = "local";
    private final static int RETRY_THREADS_NUMBER = 50;
    private final static int RESPONSE_THREADS_NUMBER = 50;
    private final static int SUSPICIOUS_NODE_RETRY_PERIOD = 30_000;

    private final Set<String> endpoints;
    private final int retryNumber;
    private final int timeout;
    private final int retryPeriod;
    private final HealthService healthService;
    private final MessageRepository messageRepository;
    private final WebClient webClient;
    private final Scheduler retryScheduler;
    private final Scheduler responseScheduler;
    private final AtomicLong messageClock;

    public HttpReplicationService(Set<String> replicasHosts,
                                  Integer retryNumber,
                                  Integer timeout,
                                  Integer retryPeriod,
                                  HealthService healthService,
                                  MessageRepository messageRepository,
                                  WebClient webClient) {
        this.endpoints = Objects.requireNonNull(replicasHosts);
        this.retryNumber = Objects.requireNonNull(retryNumber);
        this.timeout = Objects.requireNonNull(timeout);
        this.retryPeriod = Objects.requireNonNull(retryPeriod);
        this.healthService = Objects.requireNonNull(healthService);
        this.messageRepository = Objects.requireNonNull(messageRepository);
        this.webClient = Objects.requireNonNull(webClient);
        this.retryScheduler = Schedulers.newParallel("retry-parallel", RETRY_THREADS_NUMBER);
        this.responseScheduler = Schedulers.newParallel("response-parallel", RESPONSE_THREADS_NUMBER);
        this.messageClock = new AtomicLong();
    }

    public Mono<Void> replicateMessage(String payload, int replicationConcern) {
        if (!healthService.isHasQuorum()) {
            return Mono.error(new InternalServerException("Write operation deprecated until quorum is reached."));
        }

        final Message message = Message.of(payload, messageClock.incrementAndGet());
        logger.info("Sending message {} with concern {}", message, replicationConcern);

        if (replicationConcern > endpoints.size() + 1) {
            throw new InvalidRequestException(String.format("Failed to replicate message `%s`, reason: "
                            + "replication concern parameter `%d` exceeded hosts number `%d`",
                    message, replicationConcern, endpoints.size()));
        }

        final CountDownLatch countDownLatch = new CountDownLatch(replicationConcern);
        return Mono.just(1)
                .subscribeOn(responseScheduler)
                .doOnNext(s -> awaitOnCountDownLatch(countDownLatch))
                .doOnSubscribe(ignored -> Flux.concat(Mono.just(LOCAL_ADDRESS), Flux.fromIterable(endpoints))
                        .parallel()
                        .runOn(retryScheduler)
                        .flatMap(host -> persistReplica(message, host, retryNumber, countDownLatch))
                        .doOnNext(unused -> countDownLatch.countDown())
                        .subscribe())
                .then();
    }

    private Mono<ClientResponse> persistReplica(Message message, String host, int retryNumber,
                                                CountDownLatch countDownLatch) {
        if (host.equals(LOCAL_ADDRESS)) {
            return saveReplicaToLocally(message);
        } else {
            return sendReplicaToRemoteHost(message, host, retryNumber, countDownLatch);
        }
    }

    private Mono<ClientResponse> saveReplicaToLocally(Message message) {
        messageRepository.persistMessage(message);
        return Mono.just(ClientResponse.create(HttpStatus.OK).build());
    }

    private Mono<ClientResponse> sendReplicaToRemoteHost(Message message, String host, int retryNumber,
                                                         CountDownLatch countDownLatch) {
        return Mono.just(host)
                .flatMap(healthyNode -> doRequest(host, message, countDownLatch))
                // just simulation of smart retry on suspicious node
                .retryWhen(Retry.fixedDelay(retryNumber, Duration.ofMillis(SUSPICIOUS_NODE_RETRY_PERIOD))
                        .filter(throwable -> throwable instanceof FailedConnectionException))
                .retryWhen(Retry.fixedDelay(retryNumber, Duration.ofMillis(retryPeriod))
                        .filter(throwable -> !(throwable instanceof DeadNodeException)));

    }

    private Mono<ClientResponse> doRequest(String host, Message message, CountDownLatch countDownLatch) {
        if (healthService.isNodeHealthy(host)) {
            return webClient.post()
                    .uri(buildReplicationEndpoint(host, REPLICATION_PATH))
                    .body(Mono.just(message), Message.class)
                    .exchange()
                    .timeout(Duration.ofMillis(timeout),
                            Mono.just(ClientResponse.create(HttpStatus.REQUEST_TIMEOUT).build()))
                    .onErrorResume(throwable -> Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
                            .build()))
                    .flatMap(clientResponse -> handleErrorResponse(message, host, clientResponse));
        } else {
            logger.info("Retry for not responsive node {} with message = {}", host, message);
            if (healthService.isNodeCrashed(host)) {
                endpoints.remove(host);
                countDownLatch.countDown();
                logger.info(String.format("Host %s is dead and removed from cluster", host));
                return Mono.error(new DeadNodeException(String.format("Host %s is dead and removed from cluster",
                        host)));
            } else {
                return Mono.error(new FailedConnectionException(String.format("Host %s is down", host)));
            }
        }
    }

    private String buildReplicationEndpoint(String host, String replicationPath) {
        return host + replicationPath;
    }

    private void awaitOnCountDownLatch(CountDownLatch countDownLatch) {
        try {
            countDownLatch.await();
        } catch (InterruptedException e) {
            throw new InternalServerException("Failed while waiting for replication concern number to respond");
        }
    }

    private Mono<ClientResponse> handleErrorResponse(Message message, String host, ClientResponse clientResponse) {
        final HttpStatus statusCode = clientResponse.statusCode();
        logger.info("Status = {}, message = {}, host = {}", statusCode.value(), message, host);
        if ((statusCode.is5xxServerError() || statusCode.is4xxClientError())
                && statusCode.value() != HttpStatus.CONFLICT.value()) {
            return Mono.error(new InternalServerException("Failed to call service"));
        } else {
            return Mono.just(clientResponse);
        }
    }

    public Mono<Collection<String>> getMessages() {
        return messageRepository.readAll();
    }
}
