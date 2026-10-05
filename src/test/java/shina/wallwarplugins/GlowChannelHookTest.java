package shina.wallwarplugins;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GlowChannelHookTest {
    private static EmbeddedChannel channel() {
        var channel = new EmbeddedChannel();
        channel.pipeline().addLast("packet_handler", new ChannelDuplexHandler());
        return channel;
    }

    @Test void reproducesOriginalMissingAnchor() {
        var channel = new EmbeddedChannel();
        try {
            assertThrows(NoSuchElementException.class,
                    () -> channel.pipeline().addBefore("packet_handler", "glow", new ChannelDuplexHandler()));
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void missingAnchorRetriesBoundedlyWithoutThrowing() {
        var channel = new EmbeddedChannel();
        var warnings = new ArrayList<String>();
        var hook = new GlowChannelHook(channel, "glow", new ChannelDuplexHandler(), warnings::add);
        try {
            assertDoesNotThrow(hook::install);
            for (int i = 0; i < 4; i++) {
                channel.advanceTimeBy(51, TimeUnit.MILLISECONDS);
                channel.runScheduledPendingTasks();
            }
            assertFalse(hook.isInstalled());
            assertNull(channel.pipeline().get("glow"));
            assertEquals(1, warnings.size());
            assertTrue(channel.isOpen());
        } finally { hook.close(); channel.finishAndReleaseAll(); }
    }

    @Test void repeatedInstallDoesNotDuplicatePendingRetries() {
        var channel = new EmbeddedChannel();
        var warnings = new ArrayList<String>();
        var hook = new GlowChannelHook(channel, "glow", new ChannelDuplexHandler(), warnings::add);
        try {
            hook.install(); hook.install(); hook.install();
            for (int i = 0; i < 4; i++) {
                channel.advanceTimeBy(51, TimeUnit.MILLISECONDS); channel.runScheduledPendingTasks();
            }
            assertEquals(1, warnings.size());
        } finally { hook.close(); channel.finishAndReleaseAll(); }
    }

    @Test void renamedConnectionHandlerIsRecognized() {
        var channel = new EmbeddedChannel();
        channel.pipeline().addLast("renamed_connection", new net.minecraft.network.Connection());
        var hook = new GlowChannelHook(channel, "glow", new ChannelDuplexHandler(), fail -> fail(fail));
        try {
            assertEquals("renamed_connection", GlowChannelHook.anchor(channel.pipeline()));
            hook.install(); assertTrue(hook.isInstalled());
        } finally { hook.close(); channel.finishAndReleaseAll(); }
    }

    @Test void arbitraryEncoderIsNotUsedAsAnAnchor() {
        var channel = new EmbeddedChannel();
        channel.pipeline().addLast("encoder", new ChannelDuplexHandler());
        try { assertNull(GlowChannelHook.anchor(channel.pipeline())); }
        finally { channel.finishAndReleaseAll(); }
    }

    @Test void installsAndRemovesOnRealEventLoopFromCallingThread() throws Exception {
        var group = new DefaultEventLoopGroup(1);
        var channel = new LocalChannel() {
            @Override public boolean isActive() { return isOpen(); }
        };
        var installed = new CountDownLatch(1); var removed = new CountDownLatch(1);
        var correctThread = new AtomicBoolean(true);
        try {
            group.register(channel).sync();
            channel.pipeline().addLast("packet_handler", new ChannelDuplexHandler());
            var handler = new ChannelDuplexHandler() {
                @Override public void handlerAdded(ChannelHandlerContext context) {
                    correctThread.set(context.executor().inEventLoop()); installed.countDown();
                }
                @Override public void handlerRemoved(ChannelHandlerContext context) {
                    correctThread.set(correctThread.get() && context.executor().inEventLoop()); removed.countDown();
                }
            };
            var hook = new GlowChannelHook(channel, "glow", handler, fail -> fail(fail));
            assertFalse(channel.eventLoop().inEventLoop());
            hook.install(); assertTrue(installed.await(5, TimeUnit.SECONDS));
            hook.close(); assertTrue(removed.await(5, TimeUnit.SECONDS));
            assertTrue(correctThread.get());
        } finally {
            channel.close().sync(); group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
        }
    }

    @Test void installsBeforePacketHandlerAndIsIdempotent() {
        var channel = channel();
        var handler = new ChannelDuplexHandler();
        var hook = new GlowChannelHook(channel, "glow", handler, fail -> fail(fail));
        try {
            hook.install(); hook.install();
            assertTrue(hook.isInstalled());
            assertSame(handler, channel.pipeline().get("glow"));
            assertTrue(channel.pipeline().names().indexOf("glow") < channel.pipeline().names().indexOf("packet_handler"));
            hook.close(); hook.close();
            assertNull(channel.pipeline().get("glow"));
            assertFalse(hook.isInstalled());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void skipsClosedChannel() {
        var channel = channel(); channel.close();
        var hook = new GlowChannelHook(channel, "glow", new ChannelDuplexHandler(), fail -> fail(fail));
        assertDoesNotThrow(hook::install);
        assertFalse(hook.isInstalled());
        channel.finishAndReleaseAll();
    }

    @Test void closingInstalledConnectionClearsHookState() {
        var channel = channel();
        var hook = new GlowChannelHook(channel, "glow", new ChannelDuplexHandler(), fail -> fail(fail));
        hook.install(); assertTrue(hook.isInstalled());
        channel.close(); channel.runPendingTasks();
        assertFalse(hook.isInstalled());
        assertDoesNotThrow(hook::close);
        channel.finishAndReleaseAll();
    }

    @Test void closeBeforeInstallPreventsStaleJoinTask() {
        var channel = channel();
        var hook = new GlowChannelHook(channel, "glow", new ChannelDuplexHandler(), fail -> fail(fail));
        try {
            hook.close(); hook.install();
            assertFalse(hook.isInstalled());
            assertNull(channel.pipeline().get("glow"));
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void lateAnchorIsPickedUpByRetry() {
        var channel = new EmbeddedChannel();
        var hook = new GlowChannelHook(channel, "glow", new ChannelDuplexHandler(), fail -> fail(fail));
        try {
            hook.install();
            channel.pipeline().addLast("packet_handler", new ChannelDuplexHandler());
            channel.advanceTimeBy(51, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            assertTrue(hook.isInstalled());
        } finally { hook.close(); channel.finishAndReleaseAll(); }
    }

    @Test void anchorRemovedBetweenCheckAndInsertionSkipsOnlyCosmeticHook() {
        var channel = channel(); var attempts = new AtomicInteger();
        ChannelPipeline racedPipeline = (ChannelPipeline) Proxy.newProxyInstance(
                ChannelPipeline.class.getClassLoader(), new Class<?>[]{ChannelPipeline.class}, (proxy, method, args) -> {
                    if (method.getName().equals("addBefore") && attempts.getAndIncrement() == 0) {
                        channel.pipeline().remove("packet_handler");
                    }
                    try { return method.invoke(channel.pipeline(), args); }
                    catch (InvocationTargetException exception) { throw exception.getCause(); }
                });
        Channel racedChannel = (Channel) Proxy.newProxyInstance(Channel.class.getClassLoader(),
                new Class<?>[]{Channel.class}, (proxy, method, args) -> {
                    if (method.getName().equals("pipeline")) { return racedPipeline; }
                    try { return method.invoke(channel, args); }
                    catch (InvocationTargetException exception) { throw exception.getCause(); }
                });
        var warnings = new ArrayList<String>();
        var hook = new GlowChannelHook(racedChannel, "glow", new ChannelDuplexHandler(), warnings::add);
        try {
            assertDoesNotThrow(hook::install); assertFalse(hook.isInstalled());
            channel.pipeline().addLast("packet_handler", new ChannelDuplexHandler());
            channel.advanceTimeBy(51, TimeUnit.MILLISECONDS); channel.runScheduledPendingTasks();
            assertFalse(hook.isInstalled()); assertEquals(1, warnings.size());
            assertTrue(channel.isOpen()); assertNull(channel.pipeline().get("glow"));
        } finally { hook.close(); channel.finishAndReleaseAll(); }
    }

    @Test void quitCancelsPendingRetry() {
        var channel = new EmbeddedChannel();
        var hook = new GlowChannelHook(channel, "glow", new ChannelDuplexHandler(), fail -> fail(fail));
        try {
            hook.install(); hook.close();
            channel.pipeline().addLast("packet_handler", new ChannelDuplexHandler());
            channel.advanceTimeBy(1000, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            assertFalse(hook.isInstalled());
            assertNull(channel.pipeline().get("glow"));
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void disconnectDuringRetryDoesNotReinject() {
        var channel = new EmbeddedChannel();
        var warnings = new ArrayList<String>();
        var hook = new GlowChannelHook(channel, "glow", new ChannelDuplexHandler(), warnings::add);
        hook.install(); channel.close();
        channel.advanceTimeBy(1000, TimeUnit.MILLISECONDS); channel.runScheduledPendingTasks();
        assertFalse(hook.isInstalled()); assertTrue(warnings.isEmpty());
        hook.close(); channel.finishAndReleaseAll();
    }

    @Test void neverRemovesForeignHandler() {
        var channel = channel(); var foreign = new ChannelDuplexHandler();
        channel.pipeline().addBefore("packet_handler", "glow", foreign);
        var warnings = new ArrayList<String>();
        var hook = new GlowChannelHook(channel, "glow", new ChannelDuplexHandler(), warnings::add);
        try {
            hook.install(); hook.close();
            assertFalse(hook.isInstalled()); assertSame(foreign, channel.pipeline().get("glow"));
            assertEquals(1, warnings.size());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void replacementUsesOriginalPromiseAndDoesNotReenter() {
        var calls = new AtomicInteger();
        var channel = new EmbeddedChannel(new GlowPacketHandler(message -> {
            calls.incrementAndGet(); return "rewritten";
        }, fail -> fail(fail)));
        try {
            ChannelPromise promise = channel.newPromise();
            channel.writeAndFlush("original", promise);
            assertTrue(promise.isSuccess()); assertEquals(1, calls.get());
            assertEquals("rewritten", channel.readOutbound()); assertNull(channel.readOutbound());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void reflectionFailurePassesOriginalAndWarnsOnce() {
        var warnings = new ArrayList<Exception>();
        var channel = new EmbeddedChannel(new GlowPacketHandler(message -> {
            throw new ReflectiveOperationException("incompatible metadata");
        }, warnings::add));
        try {
            assertTrue(channel.writeOutbound("first", "second"));
            assertEquals("first", channel.readOutbound()); assertEquals("second", channel.readOutbound());
            assertEquals(1, warnings.size()); assertTrue(channel.isOpen());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void runtimeFailurePassesOriginal() {
        var channel = new EmbeddedChannel(new GlowPacketHandler(message -> {
            throw new IllegalStateException("bad cosmetic state");
        }, ignored -> { }));
        try {
            ChannelPromise promise = channel.newPromise(); channel.writeAndFlush("original", promise);
            assertTrue(promise.isSuccess()); assertEquals("original", channel.readOutbound());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void downstreamFailureStillCompletesOriginalPromiseAsFailed() {
        var channel = new EmbeddedChannel(new ChannelDuplexHandler() {
            @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                promise.setFailure(new IllegalStateException("downstream rejected"));
            }
        }, new GlowPacketHandler(message -> "rewritten", fail -> fail(fail)));
        try {
            ChannelPromise promise = channel.newPromise(); channel.writeAndFlush("original", promise);
            assertTrue(promise.isDone()); assertFalse(promise.isSuccess());
            assertEquals("downstream rejected", promise.cause().getMessage());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void nullReplacementForwardsOriginalExactlyOnce() {
        var channel = new EmbeddedChannel(new GlowPacketHandler(message -> null, fail -> fail(fail)));
        try {
            assertTrue(channel.writeOutbound("original"));
            assertEquals("original", channel.readOutbound()); assertNull(channel.readOutbound());
        } finally { channel.finishAndReleaseAll(); }
    }
}
