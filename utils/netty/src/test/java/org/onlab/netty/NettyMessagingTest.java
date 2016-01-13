package org.onlab.netty;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.onlab.packet.IpAddress;
import org.onosproject.store.cluster.messaging.Endpoint;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Uninterruptibles;

import static org.junit.Assert.*;

/**
 * Unit tests for NettyMessaging.
 */
public class NettyMessagingTest {

    NettyMessaging netty1;
    NettyMessaging netty2;

    Endpoint ep1 = new Endpoint(IpAddress.valueOf("127.0.0.1"), 5001);
    Endpoint ep2 = new Endpoint(IpAddress.valueOf("127.0.0.1"), 5002);
    Endpoint invalidEndPoint = new Endpoint(IpAddress.valueOf("127.0.0.1"), 5003);

    @Before
    public void setUp() throws Exception {
        netty1 = new NettyMessaging();
        netty2 = new NettyMessaging();

        netty1.start(12, ep1);
        netty2.start(12, ep2);
    }

    @After
    public void tearDown() throws Exception {
        if (netty1 != null) {
            netty1.stop();
        }

        if (netty2 != null) {
            netty2.stop();
        }
    }

    @Test
    public void testSendAsync() {
        CountDownLatch latch1 = new CountDownLatch(1);
        CompletableFuture<Void> response = netty1.sendAsync(ep2, "test-subject", "hello world".getBytes());
        response.whenComplete((r, e) -> {
            assertNull(e);
            latch1.countDown();
        });
        Uninterruptibles.awaitUninterruptibly(latch1);

        CountDownLatch latch2 = new CountDownLatch(1);
        response = netty1.sendAsync(invalidEndPoint, "test-subject", "hello world".getBytes());
        response.whenComplete((r, e) -> {
            assertNotNull(e);
            latch2.countDown();
        });
        Uninterruptibles.awaitUninterruptibly(latch2);
    }

    @Test
    public void testSendAndReceive() {
        AtomicBoolean handlerInvoked = new AtomicBoolean(false);
        AtomicReference<byte[]> request = new AtomicReference<>();
        AtomicReference<Endpoint> sender = new AtomicReference<>();

        BiFunction<Endpoint, byte[], byte[]> handler = (ep, data) -> {
            handlerInvoked.set(true);
            sender.set(ep);
            request.set(data);
            return "hello there".getBytes();
        };
        netty2.registerHandler("test-subject", handler, MoreExecutors.directExecutor());

        CompletableFuture<byte[]> response = netty1.sendAndReceive(ep2, "test-subject", "hello world".getBytes());
        assertTrue(Arrays.equals("hello there".getBytes(), response.join()));
        assertTrue(handlerInvoked.get());
        assertTrue(Arrays.equals(request.get(), "hello world".getBytes()));
        assertEquals(ep1, sender.get());
    }

    /*
     * Supplies executors when registering a handler and calling sendAndReceive and verifies the request handling
     * and response completion occurs on the expected thread.
     */
    @Test
    public void testSendAndReceiveWithExecutor() {
        ExecutorService completionExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "completion-thread"));
        ExecutorService handlerExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "handler-thread"));
        AtomicReference<String> handlerThreadName = new AtomicReference<>();
        AtomicReference<String> completionThreadName = new AtomicReference<>();

        BiFunction<Endpoint, byte[], byte[]> handler = (ep, data) -> {
            handlerThreadName.set(Thread.currentThread().getName());
            return "hello there".getBytes();
        };
        netty2.registerHandler("test-subject", handler, handlerExecutor);

        CompletableFuture<byte[]> response = netty1.sendAndReceive(ep2,
                "test-subject",
                "hello world".getBytes(),
                completionExecutor);
        response.whenComplete((r, e) -> {
            completionThreadName.set(Thread.currentThread().getName());
        });

        // Verify that the message was request handling and response completion happens on the correct thread.
        assertTrue(Arrays.equals("hello there".getBytes(), response.join()));
        assertEquals("completion-thread", completionThreadName.get());
        assertEquals("handler-thread", handlerThreadName.get());
    }
}
