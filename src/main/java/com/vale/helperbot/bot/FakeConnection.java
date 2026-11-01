package com.vale.helperbot.bot;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/**
 * A connection with no socket behind it. Every outgoing packet is dropped, and nothing that
 * would touch the (null) netty channel is allowed to run.
 */
public class FakeConnection extends Connection {
	public FakeConnection() {
		super(PacketFlow.SERVERBOUND);
	}

	@Override
	public void send(Packet<?> packet) {
	}

	@Override
	public void send(Packet<?> packet, ChannelFutureListener listener) {
	}

	@Override
	public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
	}

	@Override
	public void flushChannel() {
	}

	@Override
	public void tick() {
	}

	@Override
	public void disconnect(Component reason) {
	}

	@Override
	public void disconnect(DisconnectionDetails details) {
	}

	@Override
	public void setReadOnly() {
	}

	@Override
	public void handleDisconnection() {
	}

	@Override
	public void setListenerForServerboundHandshake(PacketListener listener) {
	}

	@Override
	public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocolInfo, T listener) {
	}

	@Override
	public void setupOutboundProtocol(ProtocolInfo<?> protocolInfo) {
	}
}
