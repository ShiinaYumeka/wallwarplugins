package shina.wallwarplugins;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import java.util.function.Consumer;

/** Cosmetic rewrites forward exactly once, retaining the original outbound promise. */
final class GlowPacketHandler extends ChannelDuplexHandler {
    @FunctionalInterface
    interface Rewrite {
        Object apply(Object packet) throws ReflectiveOperationException;
    }

    private final Rewrite rewrite;
    private final Consumer<Exception> onFailure;
    private boolean failureReported;

    GlowPacketHandler(Rewrite rewrite, Consumer<Exception> onFailure) {
        this.rewrite = rewrite;
        this.onFailure = onFailure;
    }

    @Override
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
        Object outbound = message;
        try {
            Object replacement = rewrite.apply(message);
            if (replacement != null) {
                outbound = replacement;
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            // Failure of a visual effect must not discard the original packet or disconnect the player.
            if (!failureReported) {
                failureReported = true;
                onFailure.accept(exception);
            }
        }
        // Continue from this context; sending through NMS again can re-enter the hook and lose the promise.
        super.write(context, outbound, promise);
    }
}
