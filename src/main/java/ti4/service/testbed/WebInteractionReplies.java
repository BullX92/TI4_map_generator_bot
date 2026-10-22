package ti4.service.testbed;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.apache.commons.lang3.reflect.MethodUtils;

final class WebInteractionReplies {
    private Message original;
    private boolean originalResolved;

    Object create(
            Class<?> actionType,
            JDA jda,
            TestBedPress.Recorder recorder,
            @Nullable TestBedPress.Repost destination,
            @Nullable Object callbackResult,
            boolean initialReply) {
        return create(actionType, jda, recorder, destination, callbackResult, initialReply, () -> {});
    }

    Object create(
            Class<?> actionType,
            JDA jda,
            TestBedPress.Recorder recorder,
            @Nullable TestBedPress.Repost destination,
            @Nullable Object callbackResult,
            boolean initialReply,
            Runnable acknowledge) {
        MessageCreateBuilder builder = new MessageCreateBuilder();
        return action(actionType, jda, recorder, builder, () -> {
            if (builder.isEmpty() && callbackResult != null) {
                acknowledge.run();
                return callbackResult;
            }
            Message sent = send(builder.build(), destination, recorder);
            if (initialReply && !originalResolved) {
                original = sent;
                originalResolved = true;
            }
            acknowledge.run();
            return callbackResult != null ? callbackResult : sent;
        });
    }

    Object acknowledge(
            Class<?> type, JDA jda, TestBedPress.Recorder recorder, @Nullable Object result, Runnable acknowledge) {
        return action(type, jda, recorder, null, () -> {
            acknowledge.run();
            return result;
        });
    }

    Object editSource(
            Class<?> type, Message source, TestBedPress.Recorder recorder, Object replyHook, Runnable acknowledge) {
        MessageEditBuilder builder = new MessageEditBuilder();
        return action(type, source.getJDA(), recorder, builder, () -> {
            if (!builder.isEmpty()) {
                MessageEditData data = builder.build();
                recorder.reply(data.getContent());
                source.editMessage(data).complete();
            }
            acknowledge.run();
            return replyHook;
        });
    }

    Object edit(
            Method method,
            @Nullable Object[] args,
            JDA jda,
            TestBedPress.Recorder recorder,
            @Nullable TestBedPress.Repost destination)
            throws Throwable {
        MessageEditBuilder builder = new MessageEditBuilder();
        String setter =
                switch (method.getName()) {
                    case "editOriginal" -> args[0] instanceof MessageEditData ? "applyData" : "setContent";
                    case "editOriginalComponents" -> "setComponents";
                    case "editOriginalEmbeds" -> "setEmbeds";
                    case "editOriginalAttachments" -> "setAttachments";
                    default -> throw new UnsupportedOperationException(method.getName());
                };
        Method target =
                MethodUtils.getMatchingAccessibleMethod(MessageEditBuilder.class, setter, method.getParameterTypes());
        if (target == null) throw new UnsupportedOperationException(method.getName());
        invoke(target, builder, args);
        return action(method.getReturnType(), jda, recorder, builder, () -> {
            MessageEditData edit = builder.build();
            if (original == null) {
                if (originalResolved) throw new IllegalStateException("The private interaction response was deleted.");
                MessageCreateBuilder first = MessageCreateBuilder.fromEditData(edit);
                original = send(first.build(), destination, recorder);
                originalResolved = true;
            } else {
                recorder.reply(edit.getContent());
                original = original.editMessage(edit).complete();
            }
            return original;
        });
    }

    Object retrieve(Class<?> type, JDA jda, TestBedPress.Recorder recorder) {
        return action(type, jda, recorder, null, () -> {
            if (original == null) throw new IllegalStateException("No private interaction response has been sent.");
            original =
                    original.getChannel().retrieveMessageById(original.getId()).complete();
            return original;
        });
    }

    Object delete(Class<?> type, JDA jda, TestBedPress.Recorder recorder) {
        return action(type, jda, recorder, null, () -> {
            if (original != null) {
                original.delete().complete();
                original = null;
            }
            originalResolved = true;
            return null;
        });
    }

    private static Message send(
            MessageCreateData data, @Nullable TestBedPress.Repost destination, TestBedPress.Recorder recorder) {
        if (destination == null || destination.diagnosticLabel()) {
            throw new IllegalStateException("The authenticated player's private response channel is required.");
        }
        Message message = destination.channel().sendMessage(data).complete();
        if (message == null) throw new IllegalStateException("Discord did not return the private response message.");
        recorder.reply(data.getContent());
        recorder.reposted(message.getJumpUrl());
        return message;
    }

    private static Object action(
            Class<?> type,
            JDA jda,
            TestBedPress.Recorder recorder,
            @Nullable Object builder,
            Supplier<Object> operation) {
        return Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, new java.lang.reflect.InvocationHandler() {
                    private boolean executed;
                    private Object result;
                    private Throwable failure;

                    private synchronized Object execute() throws Throwable {
                        if (!executed) {
                            executed = true;
                            try {
                                result = operation.get();
                                if (result instanceof Message response)
                                    result = synchronousMessage(response, jda, recorder);
                            } catch (Throwable error) {
                                failure = error;
                                recorder.transportFailed(error);
                            }
                        }
                        if (failure != null) throw failure;
                        return result;
                    }

                    @Override
                    public Object invoke(Object self, Method method, Object[] args) throws Throwable {
                        String name = method.getName();
                        if ("queue".equals(name)) {
                            Object value;
                            try {
                                value = execute();
                            } catch (Throwable error) {
                                if (args != null && args.length > 1 && args[1] instanceof Consumer<?> callback) {
                                    accept(callback, error);
                                    return null;
                                }
                                throw error;
                            }
                            if (args != null && args.length > 0 && args[0] instanceof Consumer<?> callback)
                                accept(callback, value);
                            return null;
                        }
                        if ("complete".equals(name)) return execute();
                        if ("submit".equals(name)) {
                            try {
                                return CompletableFuture.completedFuture(execute());
                            } catch (Throwable error) {
                                return CompletableFuture.failedFuture(error);
                            }
                        }
                        if ("getJDA".equals(name)) return jda;
                        if ("toString".equals(name)) return "WebPrivateReplyAction[" + type.getSimpleName() + "]";
                        if ("hashCode".equals(name)) return System.identityHashCode(self);
                        if ("equals".equals(name)) return args != null && args[0] == self;
                        if ("setEphemeral".equals(name)) return self;
                        Method target = builder == null
                                ? null
                                : MethodUtils.getMatchingAccessibleMethod(
                                        builder.getClass(), name, method.getParameterTypes());
                        if (target != null) {
                            Object value = WebInteractionReplies.invoke(target, builder, args);
                            return value == builder ? self : value;
                        }
                        if (method.isDefault())
                            return java.lang.reflect.InvocationHandler.invokeDefault(self, method, args);
                        throw new UnsupportedOperationException("Unsupported private reply action: " + name);
                    }
                });
    }

    private static Message synchronousMessage(Message real, JDA jda, TestBedPress.Recorder recorder) {
        return (Message) Proxy.newProxyInstance(
                Message.class.getClassLoader(), new Class<?>[] {Message.class}, (self, method, args) -> {
                    if ("equals".equals(method.getName())) return args != null && args[0] == self;
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(self);
                    Object value = invoke(method, real, args);
                    if (value instanceof RestAction<?> request
                            && (method.getName().startsWith("edit") || "delete".equals(method.getName()))) {
                        return action(method.getReturnType(), jda, recorder, request, request::complete);
                    }
                    return value;
                });
    }

    @SuppressWarnings("unchecked")
    private static void accept(Consumer<?> callback, Object value) {
        ((Consumer<Object>) callback).accept(value);
    }

    private static Object invoke(Method method, Object target, @Nullable Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }
}
