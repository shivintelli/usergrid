/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.usergrid.persistence.qakka.distributed.impl;

import akka.actor.ActorRef;
import akka.pattern.Patterns;
import akka.util.Timeout;
import com.datastax.driver.core.exceptions.InvalidQueryException;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Singleton;
import org.apache.usergrid.persistence.actorsystem.ActorSystemManager;
import org.apache.usergrid.persistence.actorsystem.ClientActor;
import org.apache.usergrid.persistence.actorsystem.GuiceActorProducer;
import org.apache.usergrid.persistence.qakka.QakkaFig;
import org.apache.usergrid.persistence.qakka.core.QueueManager;
import org.apache.usergrid.persistence.qakka.distributed.DistributedQueueService;
import org.apache.usergrid.persistence.qakka.distributed.messages.*;
import org.apache.usergrid.persistence.qakka.exceptions.NotFoundException;
import org.apache.usergrid.persistence.qakka.exceptions.QakkaRuntimeException;
import org.apache.usergrid.persistence.qakka.serialization.queuemessages.DatabaseQueueMessage;
import org.apache.usergrid.persistence.qakka.serialization.queuemessages.MessageCounterSerialization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.concurrent.Await;
import scala.concurrent.Future;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.TimeUnit;


@Singleton
public class DistributedQueueServiceImpl implements DistributedQueueService {

    private static final Logger logger = LoggerFactory.getLogger( DistributedQueueServiceImpl.class );

    private final ActorSystemManager actorSystemManager;
    private final QueueManager queueManager;
    private final QakkaFig qakkaFig;


    @Inject
    public DistributedQueueServiceImpl(
            Injector injector,
            ActorSystemManager actorSystemManager,
            QueueManager queueManager,
            QakkaFig qakkaFig
            ) {

        this.actorSystemManager = actorSystemManager;
        this.queueManager = queueManager;
        this.qakkaFig = qakkaFig;

        GuiceActorProducer.INJECTOR = injector;
    }


    @Override
    public void init() {

        try {
            List<String> queues = queueManager.getListOfQueues();
            for (String queueName : queues) {
                initQueue( queueName );
            }
        } catch (InvalidQueryException e) {

            if (e.getMessage().contains( "unconfigured columnfamily" )) {
                logger.info( "Unable to initialize queues since system is bootstrapping.  " +
                    "Queues will be initialized when created" );
            } else {
                throw e;
            }

        }

        StringBuilder logMessage = new StringBuilder();
        logMessage.append( "DistributedQueueServiceImpl initialized with config:\n" );
        Method[] methods = qakkaFig.getClass().getMethods();
        for ( Method method : methods ) {
            if ( method.getName().startsWith("get")) {
                try {
                    logMessage.append("   ")
                        .append( method.getName().substring(3) )
                        .append(" = ")
                        .append( method.invoke( qakkaFig ).toString() ).append("\n");
                } catch (Exception ignored ) {}
            }
        }
        logger.info( logMessage.toString() );
    }


    @Override
    public void initQueue(String queueName) {
        logger.info("Initializing queue: {}", queueName);
        QueueInitRequest request = new QueueInitRequest( queueName );
        ActorRef clientActor = actorSystemManager.getClientActor();
        clientActor.tell( request, null );
    }


    @Override
    public void refresh() {
        for ( String queueName : queueManager.getListOfQueues() ) {
            refreshQueue( queueName );
        }
    }


    @Override
    public void refreshQueue(String queueName) {
        logger.info("{} Requesting refresh for queue: {}", this, queueName);
        QueueRefreshRequest request = new QueueRefreshRequest( queueName, false );
        ActorRef clientActor = actorSystemManager.getClientActor();
        clientActor.tell( request, null );
    }


    @Override
    public void processTimeouts() {

        for ( String queueName : queueManager.getListOfQueues() ) {

            QueueTimeoutRequest request = new QueueTimeoutRequest( queueName );

            ActorRef clientActor = actorSystemManager.getClientActor();
            clientActor.tell( request, null );
        }
    }


    @Override
    public DistributedQueueService.Status sendMessageToRegion(
            String queueName, String sourceRegion, String destRegion, UUID messageId,
            Long deliveryTime, Long expirationTime ) {

        if ( queueManager.getQueueConfig( queueName ) == null ) {
            throw new NotFoundException( "Queue not found: " + queueName );
        }

        int maxRetries = qakkaFig.getMaxSendRetries();
        int retries = 0;

        QueueSendRequest request = new QueueSendRequest(
                queueName, sourceRegion, destRegion, messageId, deliveryTime, expirationTime );

        while ( retries++ < maxRetries ) {
            try {
                Timeout t = new Timeout( qakkaFig.getSendTimeoutSeconds(), TimeUnit.SECONDS );

                // send to current region via local clientActor
                ActorRef clientActor = actorSystemManager.getClientActor();
                Future<Object> fut = Patterns.ask( clientActor, request, t );

                // wait for response...
                final Object response = Await.result( fut, t.duration() );

                if ( response != null && response instanceof QueueSendResponse) {
                    QueueSendResponse qarm = (QueueSendResponse)response;

                    if ( !DistributedQueueService.Status.ERROR.equals( qarm.getSendStatus() )) {

                        if ( retries > 1 ) {
                            logger.debug("SUCCESS after {} retries", retries );
                        }

                        // send refresh-queue-if-empty message
                        QueueRefreshRequest qrr = new QueueRefreshRequest( queueName, false );
                        clientActor.tell( qrr, null );

                        return qarm.getSendStatus();

                    } else {
                        logger.debug("ERROR STATUS sending to queue, retrying {}", retries );
                    }

                } else if ( response != null  ) {
                    logger.debug("NULL RESPONSE sending to queue, retrying {}", retries );

                } else {
                    logger.debug("TIMEOUT sending to queue, retrying {}", retries );
                }

            } catch ( Exception e ) {
                logger.debug("ERROR sending to queue, retrying " + retries, e );
            }
        }

        throw new QakkaRuntimeException( "Error sending to queue after " + retries );
    }


    @Override
    public Collection<DatabaseQueueMessage> getNextMessages( String queueName, int count ) {
        List<DatabaseQueueMessage> ret = new ArrayList<>();

        long startTime = System.currentTimeMillis();

        while ( ret.size() < count
            && System.currentTimeMillis() - startTime < qakkaFig.getLongPollTimeMillis()) {

            ret.addAll( getNextMessagesInternal( queueName, count ));

            if ( ret.size() < count ) {
                try { Thread.sleep( qakkaFig.getLongPollTimeMillis() / 2 ); } catch (Exception ignored) {}
            }
        }

        return ret;
    }


    public Collection<DatabaseQueueMessage> getNextMessagesInternal( String queueName, int count ) {

        if ( queueManager.getQueueConfig( queueName ) == null ) {
            throw new NotFoundException( "Queue not found: " + queueName );
        }

        if ( actorSystemManager.getClientActor() == null || !actorSystemManager.isReady() ) {
            logger.error("Akka Actor System is not ready yet for requests.");
            return Collections.EMPTY_LIST;
        }

        int maxRetries = qakkaFig.getMaxGetRetries();
        int retries = 0;

        QueueGetRequest request = new QueueGetRequest( queueName, count );
        while ( ++retries < maxRetries ) {
            try {
                Timeout t = new Timeout( qakkaFig.getGetTimeoutSeconds(), TimeUnit.SECONDS );

                // ask ClientActor and wait (up to timeout) for response

                Future<Object> fut = Patterns.ask( actorSystemManager.getClientActor(), request, t );
                Object responseObject = Await.result( fut, t.duration() );

                if ( responseObject instanceof QakkaMessage ) {

                    final QakkaMessage response = (QakkaMessage)Await.result( fut, t.duration() );

                    if ( response != null && response instanceof QueueGetResponse) {
                        QueueGetResponse qprm = (QueueGetResponse)response;
                        if ( qprm.isSuccess() ) {
                            if (retries > 1) {
                                logger.debug( "getNextMessage {} SUCCESS after {} retries", queueName, retries );
                            }
                        }
                        logger.debug("Returning queue {} messages {}", queueName, qprm.getQueueMessages().size());
                        return qprm.getQueueMessages();


                    } else if ( response != null  ) {
                        logger.debug("ERROR RESPONSE (1) popping queue {}, retrying {}", queueName, retries );

                    } else {
                        logger.debug("TIMEOUT popping from queue {}, retrying {}", queueName, retries );
                    }

                } else if ( responseObject instanceof ClientActor.ErrorResponse ) {

                    final ClientActor.ErrorResponse errorResponse = (ClientActor.ErrorResponse)responseObject;
                    logger.debug("ACTORSYSTEM ERROR popping queue: {}, retrying {}",
                        errorResponse.getMessage(), retries );

                } else {
                    logger.debug("UNKNOWN RESPONSE popping queue {}, retrying {}", queueName, retries );
                }

            } catch ( Exception e ) {
                logger.debug("ERROR popping to queue " + queueName + " retrying " + retries, e );
            }
        }

        throw new QakkaRuntimeException(
                "Error getting from queue " + queueName + " after " + retries + " tries");
    }


    @Override
    public Status ackMessage(String queueName, UUID queueMessageId ) {

        if ( queueManager.getQueueConfig( queueName ) == null ) {
            throw new NotFoundException( "Queue not found: " + queueName );
        }

        QueueAckRequest message = new QueueAckRequest( queueName, queueMessageId );
        return sendMessageToLocalQueueActors( message );
    }


    @Override
    public Status requeueMessage(String queueName, UUID messageId) {

        if ( queueManager.getQueueConfig( queueName ) == null ) {
            throw new NotFoundException( "Queue not found: " + queueName );
        }

        QueueAckRequest message = new QueueAckRequest( queueName, messageId );
        return sendMessageToLocalQueueActors( message );
    }


    @Override
    public Status clearMessages(String queueName) {

        if ( queueManager.getQueueConfig( queueName ) == null ) {
            throw new NotFoundException( "Queue not found: " + queueName );
        }

        // TODO: implement clear queue
        throw new UnsupportedOperationException();
    }


    private Status sendMessageToLocalQueueActors( QakkaMessage message ) {

        int maxRetries = 5;
        int retries = 0;

        while ( retries++ < maxRetries ) {
            try {
                Timeout t = new Timeout( 1, TimeUnit.SECONDS );

                // ask ClientActor and wait (up to timeout) for response

                Future<Object> fut = Patterns.ask( actorSystemManager.getClientActor(), message, t );
                final QakkaMessage response = (QakkaMessage)Await.result( fut, t.duration() );

                if ( response != null && response instanceof QueueAckResponse) {
                    QueueAckResponse qprm = (QueueAckResponse)response;
                    return qprm.getStatus();

                } else if ( response != null  ) {
                    logger.debug("ERROR RESPONSE sending message, retrying {}", retries );

                } else {
                    logger.debug("TIMEOUT sending message, retrying {}", retries );
                }

            } catch ( Exception e ) {
                logger.debug("ERROR sending message, retrying " + retries, e );
            }
        }

        throw new QakkaRuntimeException(
                "Error sending message " + message + "after " + retries );
    }

    public void shutdown() {
        actorSystemManager.shutdownAll();
    }
}