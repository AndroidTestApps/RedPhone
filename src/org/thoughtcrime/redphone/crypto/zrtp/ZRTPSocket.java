/*
 * Copyright (C) 2011 Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.thoughtcrime.redphone.crypto.zrtp;

import android.util.Log;

import org.thoughtcrime.redphone.Release;
import org.thoughtcrime.redphone.call.CallStateListener;
import org.thoughtcrime.redphone.crypto.SecureRtpSocket;
import org.thoughtcrime.redphone.util.Conversions;

import java.io.IOException;
import java.math.BigInteger;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;

import javax.crypto.interfaces.DHPublicKey;
import javax.crypto.spec.DHParameterSpec;

/**
 * The base ZRTP socket implementation.
 *
 * ZRTPInitiatorSocket and ZRTPResponderSocket extend this to implement their respective
 * parts in the ZRTP handshake.
 *
 * This is fundamentally just a simple state machine which iterates through the ZRTP handshake.
 *
 * @author Moxie Marlinspike
 *
 */

public abstract class ZRTPSocket {

  public static final BigInteger PRIME     = new BigInteger("FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3BE39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF", 16);
  public static final BigInteger GENERATOR = new BigInteger("02", 16);

  private static final int RETRANSMIT_INTERVAL_MILLIS = 50;
  private static final int MAX_RETRANSMIT_COUNT       = 45;

  protected static final int EXPECTING_HELLO            = 0;
  protected static final int EXPECTING_HELLO_ACK        = 1;
  protected static final int EXPECTING_COMMIT           = 2;
  protected static final int EXPECTING_DH_1             = 3;
  protected static final int EXPECTING_DH_2             = 4;
  protected static final int EXPECTING_CONFIRM_ONE      = 5;
  protected static final int EXPECTING_CONFIRM_TWO      = 9;
  protected static final int HANDSHAKE_COMPLETE         = 6;
  protected static final int EXPECTING_CONFIRM_ACK      = 7;
  protected static final int TERMINATED                 = 8;

  private final CallStateListener callStateListener;

  private long transmitStartTime  = 0;
  private int  retransmitInterval = RETRANSMIT_INTERVAL_MILLIS;
  private int  retransmitCount    = 0;
  private int  sequence           = 0;
  private int  state;

  private SecureRtpSocket socket;
  private HandshakePacket lastPacket;

  protected KeyPair keyPair;
  protected HashChain hashChain;
  protected MasterSecret masterSecret;

  public ZRTPSocket(CallStateListener callStateListener, SecureRtpSocket socket, int initialState) {
    this.callStateListener = callStateListener;
    this.socket            = socket;
    this.state             = initialState;
    this.keyPair           = initializeKeys();
    this.hashChain         = new HashChain();

    this.socket.setTimeout(RETRANSMIT_INTERVAL_MILLIS);
  }

  protected abstract void handleHello(HandshakePacket packet) throws InvalidPacketException;
  protected abstract void handleCommit(HandshakePacket packet) throws InvalidPacketException;
  protected abstract void handleDH(HandshakePacket packet) throws InvalidPacketException;
  protected abstract void handleConfirmOne(HandshakePacket packet) throws InvalidPacketException;
  protected abstract void handleConfirmTwo(HandshakePacket packet) throws InvalidPacketException;
  protected abstract void handleHelloAck(HandshakePacket packet) throws InvalidPacketException;
  protected abstract void handleConfirmAck(HandshakePacket packet) throws InvalidPacketException;

  protected byte[] getPublicKey() {
    if (Release.DEBUG)
      Log.w("ZRTPSocket", "Sending public key: " + ((DHPublicKey)keyPair.getPublic()).getY());

    byte[] temp = new byte[384];
    Conversions.bigIntegerToByteArray(temp, ((DHPublicKey)keyPair.getPublic()).getY());
    return temp;
  }

  protected void setState(int state) {
    this.state = state;
  }

  protected void sendFreshPacket(HandshakePacket packet) {
    retransmitCount    = 0;
    retransmitInterval = RETRANSMIT_INTERVAL_MILLIS;
    sendPacket(packet);
  }

  private void sendPacket(HandshakePacket packet) {
    if (Release.DEBUG)
      Log.w("ZRTPSocket", "Sending Packet: " + packet);

    transmitStartTime = System.currentTimeMillis();
    this.lastPacket   = packet;

    if (packet != null) {
      packet.setSequenceNumber(this.sequence++);
      socket.send(packet);
    }
  }

  private void resendPacket() throws NegotiationFailedException {
    if (retransmitCount++ > MAX_RETRANSMIT_COUNT) {
      if (this.lastPacket != null) {
        throw new NegotiationFailedException("Retransmit threshold reached.");
      } else {
        throw new RecipientUnavailableException("Recipient unavailable.");
      }
    }

    retransmitInterval = Math.min(retransmitInterval*2, 1500);

    sendPacket(lastPacket);
  }

  private KeyPair initializeKeys() {
    try {
      KeyPairGenerator kg    = KeyPairGenerator.getInstance("DH");
      DHParameterSpec dhSpec = new DHParameterSpec(PRIME, GENERATOR);
      kg.initialize(dhSpec);

      return kg.generateKeyPair();
    } catch (InvalidAlgorithmParameterException e) {
      throw new IllegalArgumentException(e);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalArgumentException(e);
    }
  }

  private boolean isRetransmitTime() {
    return (System.currentTimeMillis() - transmitStartTime >= retransmitInterval);
  }

  private void resendPacketIfTimeout() throws NegotiationFailedException {
    if (isRetransmitTime()) {
      Log.w("ZRTPSocket", "Retransmitting after: " + retransmitInterval);
      resendPacket();
    }
  }

  public MasterSecret getMasterSecret() {
    return this.masterSecret;
  }

  public void close() {
    state = TERMINATED;
    socket.close();
  }

  public void negotiate() throws NegotiationFailedException {
    try {
      while (state != HANDSHAKE_COMPLETE && state != TERMINATED) {

        HandshakePacket packet = socket.receiveHandshakePacket();

        if( packet != null ) {
          Log.w("ZRTPSocket", "Received packet: " + (packet != null ? packet.getType() : "null"));
        }

        if      (packet == null)                                                                       resendPacketIfTimeout();
        else if ((packet.getType().equals(HelloPacket.TYPE))      && (state == EXPECTING_HELLO))       handleHello(packet);
        else if ((packet.getType().equals(HelloAckPacket.TYPE))   && (state == EXPECTING_HELLO_ACK))   handleHelloAck(packet);
        else if ((packet.getType().equals(CommitPacket.TYPE))     && (state == EXPECTING_COMMIT))      handleCommit(packet);
        else if ((packet.getType().equals(DHPartOnePacket.TYPE))  && (state == EXPECTING_DH_1))        handleDH(packet);
        else if ((packet.getType().equals(DHPartTwoPacket.TYPE))  && (state == EXPECTING_DH_2))        handleDH(packet);
        else if ((packet.getType().equals(ConfirmOnePacket.TYPE)) && (state == EXPECTING_CONFIRM_ONE)) handleConfirmOne(packet);
        else if ((packet.getType().equals(ConfirmTwoPacket.TYPE)) && (state == EXPECTING_CONFIRM_TWO)) handleConfirmTwo(packet);
        else if ((packet.getType().equals(ConfAckPacket.TYPE))    && (state == EXPECTING_CONFIRM_ACK)) handleConfirmAck(packet);
        else if (isRetransmitTime())                                                                   resendPacket();

        if (packet != null) {
          callStateListener.notifyPerformingHandshake();
        }
      }
    } catch (InvalidPacketException ipe) {
      Log.w("ZRTPSocket", ipe);
      throw new NegotiationFailedException(ipe);
    } catch (IOException ioe) {
      Log.w("ZRTPSocket", ioe);
      if (state != TERMINATED)
        throw new NegotiationFailedException(ioe);
    }

    if (state != TERMINATED)
      this.socket.setTimeout(1);
  }

}
