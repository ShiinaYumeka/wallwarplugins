package shina.wallwarplugins;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.util.concurrent.ScheduledFuture;

import java.util.concurrent.RejectedExecutionException;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** A connection-scoped hook: all pipeline changes run on that connection's event loop. */
final class GlowChannelHook implements AutoCloseable {
    private static final int MAX_RETRIES = 3;
    private final Channel channel;
    private final String name;
    private final ChannelHandler handler;
    private final Consumer<String> onFailure;
    private volatile boolean closed;
    private volatile boolean installed;
    private boolean started;
    private ScheduledFuture<?> retry;

    GlowChannelHook(Channel channel, String name, ChannelHandler handler, Consumer<String> onFailure) {
        this.channel = channel;
        this.name = name;
        this.handler = handler;
        this.onFailure = onFailure;
    }

    void install() {
        runOnEventLoop(() -> {
            if (!closed && !started) {
                started = true;
                tryInstall(0);
            }
        });
    }

    boolean isInstalled() {
        return installed;
    }

    private void tryInstall(int attempt) {
        if (closed || installed || !channel.isOpen() || !channel.isActive()) {
            return;
        }
        ChannelPipeline pipeline = channel.pipeline();
        if (pipeline.get(name) != null) {
            if (pipeline.get(name) == handler) {
                installed = true;
            } else {
                onFailure.accept("handler name already owned by another hook");
            }
            return;
        }
        String anchor = anchor(pipeline);
        if (anchor == null) {
            retryOrSkip(attempt);
            return;
        }
        // No arbitrary addLast fallback: the hook must remain on the packet-object side of the encoder.
        try {
            pipeline.addBefore(anchor, name, handler);
        } catch (NoSuchElementException removedAnchor) {
            // Also tolerate another plugin incorrectly changing the pipeline from a different thread.
            // Netty may already mark a non-sharable handler as used before failing to find the anchor.
            // Do not attempt to re-add that handler; skip this cosmetic hook for this connection.
            onFailure.accept("packet handler removed during injection; glow hook skipped");
            return;
        }
        installed = true;
        channel.closeFuture().addListener(future -> close());
    }

    private void retryOrSkip(int attempt) {
        if (closed || !channel.isOpen() || !channel.isActive()) {
            return;
        }
        if (attempt < MAX_RETRIES) {
            retry = channel.eventLoop().schedule(() -> tryInstall(attempt + 1), 50, TimeUnit.MILLISECONDS);
        } else {
            onFailure.accept("packet handler unavailable; glow hook skipped");
        }
    }

    static String anchor(ChannelPipeline pipeline) {
        if (pipeline.context("packet_handler") != null) {
            return "packet_handler";
        }
        // Compatibility with a renamed Minecraft Connection handler, without guessing encoder names.
        for (String candidate : pipeline.names()) {
            ChannelHandler current = pipeline.get(candidate);
            if (current == null) {
                continue;
            }
            for (Class<?> type = current.getClass(); type != null; type = type.getSuperclass()) {
                if (type.getName().equals("net.minecraft.network.Connection")) {
                    return candidate;
                }
            }
        }
        return null;
    }

    @Override
    public void close() {
        closed = true;
        runOnEventLoop(() -> {
            if (retry != null) {
                retry.cancel(false);
                retry = null;
            }
            if (channel.pipeline().get(name) == handler) {
                channel.pipeline().remove(name);
            }
            installed = false;
        });
    }

    private void runOnEventLoop(Runnable operation) {
        try {
            if (channel.eventLoop().inEventLoop()) {
                operation.run();
            } else {
                channel.eventLoop().execute(operation);
            }
        } catch (RejectedExecutionException ignored) {
            // A terminated connection needs neither installation nor a Bukkit scheduler exception.
            installed = false;
        }
    }
}
