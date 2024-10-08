//
// Copyright (c) 2023 ZettaScale Technology
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License 2.0 which is available at
// http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
//
// Contributors:
//   ZettaScale Zenoh Team, <zenoh@zettascale.tech>
//

package io.zenoh

import io.zenoh.handlers.Handler
import io.zenoh.keyexpr.KeyExpr
import io.zenoh.keyexpr.intoKeyExpr
import io.zenoh.prelude.*
import io.zenoh.protocol.into
import io.zenoh.query.Reply
import io.zenoh.queryable.Query
import io.zenoh.sample.Sample
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.time.withTimeout
import org.apache.commons.net.ntp.TimeStamp
import java.time.Duration
import java.time.Instant
import java.util.*
import kotlin.test.*

class QueryableTest {

    companion object {
        val testPayload = "Hello queryable".into()
    }

    private lateinit var session: Session
    private lateinit var testKeyExpr: KeyExpr

    @BeforeTest
    fun setUp() {
        session = Session.open(Config.default()).getOrThrow()
        testKeyExpr = "example/testing/keyexpr".intoKeyExpr().getOrThrow()
    }

    @AfterTest
    fun tearDown() {
        session.close()
        testKeyExpr.close()
    }

    /** Test validating both Queryable and get operations. */
    @Test
    fun queryable_runsWithCallback() = runBlocking {
        val sample = Sample(
            testKeyExpr,
            testPayload,
            Encoding.default(),
            SampleKind.PUT,
            TimeStamp(Date.from(Instant.now())),
            QoS()
        )
        val queryable = session.declareQueryable(testKeyExpr, callback = { query ->
            query.replySuccess(testKeyExpr, payload = sample.payload, timestamp = sample.timestamp)
        }).getOrThrow()

        var reply: Reply? = null
        val delay = Duration.ofMillis(1000)
        withTimeout(delay) {
            session.get(testKeyExpr.intoSelector(), callback = { reply = it }, timeout = delay)
        }

        assertTrue(reply is Reply.Success)
        assertEquals((reply as Reply.Success).sample, sample)

        queryable.close()
    }

    @Test
    fun queryable_runsWithHandler() = runBlocking {
        val handler = QueryHandler()
        val queryable = session.declareQueryable(testKeyExpr, handler = handler).getOrThrow()

        delay(500)

        val receivedReplies = ArrayList<Reply>()
        session.get(testKeyExpr.intoSelector(), callback = { reply: Reply ->
            receivedReplies.add(reply)
        })

        delay(500)

        queryable.close()
        assertTrue(receivedReplies.all { it is Reply.Success })
        assertEquals(handler.performedReplies.size, receivedReplies.size)
    }

    @Test
    fun queryTest() = runBlocking {
        var receivedQuery: Query? = null
        val queryable =
            session.declareQueryable(testKeyExpr, callback = { query -> receivedQuery = query }).getOrThrow()

        session.get(testKeyExpr.intoSelector(), callback = {})

        delay(100)
        assertNotNull(receivedQuery)
        assertNull(receivedQuery!!.payload)
        assertNull(receivedQuery!!.encoding)
        assertNull(receivedQuery!!.attachment)

        receivedQuery = null
        val payload = "Test value".into()
        val attachment = "Attachment".into()
        session.get(testKeyExpr.intoSelector(), callback = {}, payload = payload, encoding = Encoding.ZENOH_STRING, attachment = attachment)

        delay(100)
        assertNotNull(receivedQuery)
        assertEquals(payload, receivedQuery!!.payload)
        assertEquals(Encoding.ZENOH_STRING.id, receivedQuery!!.encoding!!.id)
        assertEquals(attachment, receivedQuery!!.attachment)

        queryable.close()
    }

    @Test
    fun queryReplySuccessTest() {
        val message = "Test message".into()
        val timestamp = TimeStamp.getCurrentTime()
        val qos = QoS(priority = Priority.DATA_HIGH, express = true, congestionControl = CongestionControl.DROP)
        val priority = Priority.DATA_HIGH
        val express = true
        val congestionControl = CongestionControl.DROP
        val queryable = session.declareQueryable(testKeyExpr, callback = { query ->
            query.replySuccess(testKeyExpr, payload = message, timestamp = timestamp, qos = qos)
        }).getOrThrow()

        var receivedReply: Reply? = null
        session.get(testKeyExpr.intoSelector(), callback = { receivedReply = it }, timeout = Duration.ofMillis(10))

        queryable.close()

        assertTrue(receivedReply is Reply.Success)
        val reply = receivedReply as Reply.Success
        assertEquals(message, reply.sample.payload)
        assertEquals(timestamp, reply.sample.timestamp)
        assertEquals(priority, reply.sample.qos.priority)
        assertEquals(express, reply.sample.qos.express)
        assertEquals(congestionControl, reply.sample.qos.congestionControl)
    }

    @Test
    fun queryReplyErrorTest() {
        val errorMessage = "Error message".into()
        val queryable = session.declareQueryable(testKeyExpr, callback = { query ->
            query.replyError(error = errorMessage)
        }).getOrThrow()

        var receivedReply: Reply? = null
        session.get(testKeyExpr.intoSelector(), callback =  { receivedReply = it }, timeout = Duration.ofMillis(10))

        Thread.sleep(1000)
        queryable.close()

        assertNotNull(receivedReply)
        assertTrue(receivedReply is Reply.Error)
        val reply = receivedReply as Reply.Error
        assertEquals(errorMessage, reply.error)
    }

    @Test
    fun queryReplyDeleteTest() {
        val timestamp = TimeStamp.getCurrentTime()
        val priority = Priority.DATA_HIGH
        val express = true
        val congestionControl = CongestionControl.DROP
        val qos = QoS(priority = Priority.DATA_HIGH, express = true, congestionControl = CongestionControl.DROP)

        val queryable = session.declareQueryable(testKeyExpr, callback = { query ->
            query.replyDelete(testKeyExpr, timestamp = timestamp, qos = qos)
        }).getOrThrow()
        var receivedReply: Reply? = null
        session.get(testKeyExpr.intoSelector(), callback = { receivedReply = it }, timeout = Duration.ofMillis(10))

        queryable.close()

        assertNotNull(receivedReply)
        assertTrue(receivedReply is Reply.Delete)
        val reply = receivedReply as Reply.Delete
        assertEquals(timestamp, reply.timestamp)
        assertEquals(priority, reply.qos.priority)
        assertEquals(express, reply.qos.express)
        assertEquals(congestionControl, reply.qos.congestionControl)
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun onCloseTest() = runBlocking {
        var onCloseWasCalled = false
        val channel = Channel<Query>()
        val queryable =
            session.declareQueryable(testKeyExpr, channel = channel, onClose = { onCloseWasCalled = true }).getOrThrow()
        queryable.undeclare()

        assertTrue(onCloseWasCalled)
        assertTrue(queryable.receiver.isClosedForReceive)
    }
}

/** A dummy handler that replies "Hello queryable" followed by the count of replies performed. */
private class QueryHandler : Handler<Query, QueryHandler> {

    private var counter = 0

    val performedReplies: ArrayList<Sample> = ArrayList()

    override fun handle(t: Query) {
        reply(t)
    }

    override fun receiver(): QueryHandler {
        return this
    }

    override fun onClose() {}

    fun reply(query: Query) {
        val payload = "Hello queryable $counter!".into()
        counter++
        val sample = Sample(
            query.keyExpr,
            payload,
            Encoding.default(),
            SampleKind.PUT,
            TimeStamp(Date.from(Instant.now())),
            QoS()
        )
        performedReplies.add(sample)
        query.replySuccess(query.keyExpr, payload = payload, timestamp = sample.timestamp)
    }
}
