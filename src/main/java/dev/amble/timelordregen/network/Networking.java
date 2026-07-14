package dev.amble.timelordregen.network;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

public class Networking {
    // 统一使用一个 ID：服务端 → 客户端 打开 GUI
    public static final Identifier OPEN_GUI_PACKET = new Identifier("timelordregen", "open_gui");
    // 客户端 → 服务端 请求打开 GUI
    public static final Identifier REQUEST_OPEN_GUI = new Identifier("timelordregen", "request_open_gui");

    /**
     * 在服务端注册所有 C2S（客户端→服务端）接收器
     * 在模组主类的 onInitialize() 中调用
     */
    public static void registerServerReceivers() {
        // 处理客户端请求打开 GUI
        ServerPlayNetworking.registerGlobalReceiver(REQUEST_OPEN_GUI, (server, player, handler, buf, responseSender) -> {
            // 直接发送 OPEN_GUI_PACKET 给该玩家
            sendOpenGuiPacket(player);
        });
    }

    /**
     * 服务端发送打开 GUI 数据包给指定玩家
     */
    public static void sendOpenGuiPacket(ServerPlayerEntity player) {
        ServerPlayNetworking.send(player, OPEN_GUI_PACKET, PacketByteBufs.create());
    }
}