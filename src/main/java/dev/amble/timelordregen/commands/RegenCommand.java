package dev.amble.timelordregen.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.amble.timelordregen.api.RegenerationInfo;
import dev.amble.timelordregen.network.Networking;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class RegenCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        // ===== /regen =====
        dispatcher.register(literal("regen")
                .requires(source -> source.hasPermissionLevel(0))
                .then(literal("get")
                        .executes(context -> {
                            ServerPlayerEntity player = context.getSource().getPlayer();
                            if (player == null) return 0;

                            RegenerationInfo info = getInfoOrError(context, player);
                            if (info == null) return 0;

                            context.getSource().sendFeedback(() ->
                                    Text.translatable("gui.regen.settings.remaining", info.getUsesLeft()), false);
                            return 1;
                        })
                )
                .then(literal("set")
                        .then(argument("count", IntegerArgumentType.integer(0, RegenerationInfo.MAX_REGENERATIONS))
                                .executes(context -> {
                                    ServerPlayerEntity player = context.getSource().getPlayer();
                                    if (player == null) return 0;

                                    RegenerationInfo info = getInfoOrError(context, player);
                                    if (info == null) return 0;

                                    int count = IntegerArgumentType.getInteger(context, "count");
                                    info.setUsesLeft(count);
                                    context.getSource().sendFeedback(() ->
                                            Text.translatable("gui.regen.settings.remaining", info.getUsesLeft()), false);
                                    return 1;
                                })
                        )
                )
                .then(literal("fix")
                        .executes(context -> {
                            ServerPlayerEntity player = context.getSource().getPlayer();
                            if (player == null) return 0;

                            RegenerationInfo info = getInfoOrError(context, player);
                            if (info == null) return 0;

                            info.stopRegeneration();
                            context.getSource().sendFeedback(() -> Text.translatable("command.regen.stopped"), false);
                            return 1;
                        })
                )
                .executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayer();
                    if (player == null) return 0;

                    RegenerationInfo info = getInfoOrError(context, player);
                    if (info == null) return 0;

                    if (info.tryStart(player)) {
                        context.getSource().sendFeedback(() -> Text.translatable("command.regen.triggered"), false);
                    } else {
                        context.getSource().sendError(Text.translatable("command.regen.fail"));
                    }
                    return 1;
                })
        );

        // ===== /regenui =====
        dispatcher.register(literal("regenui")
                .requires(source -> source.hasPermissionLevel(0))
                .executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayer();
                    if (player == null) return 0;
                    Networking.sendOpenGuiPacket(player);
                    return 1;
                })
        );
    }

    /**
     * 提取公共逻辑：获取玩家再生信息，失败时自动发送错误消息
     */
    private static RegenerationInfo getInfoOrError(CommandContext<ServerCommandSource> context, ServerPlayerEntity player) {
        RegenerationInfo info = RegenerationInfo.get(player);
        if (info == null) {
            context.getSource().sendError(Text.translatable("command.regen.data.error"));
        }
        return info;
    }
}