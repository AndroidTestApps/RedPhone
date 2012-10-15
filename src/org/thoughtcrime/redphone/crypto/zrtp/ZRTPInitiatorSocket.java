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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The "initiator" side of a ZRTP handshake.  This side just hangs out and waits
 * for the "responder" to send a hello packet, then proceeds through the ZRTP handshake.
 *
 * @author Moxie Marlinspike
 *
 */

public class ZRTPInitiatorSocket extends ZRTPSocket {

  private HelloPacket foreignHello;
  private HelloPacket localHello;
  private CommitPacket commitPacket;

  private DHPartOnePacket foreignDH;
  private DHPartTwoPacket localDH;

  private final byte[] zid;

  public ZRTPInitiatorSocket(CallStateListener callStateListener, SecureRtpSocket socket, byte[] zid) {
    super(callStateListener, socket, EXPECTING_HELLO);
    this.zid = zid;
  }

  @Override
  protected void handleCommit(HandshakePacket packet) {
    throw new AssertionError("Invalid state!");
  }

  @Override
  protected void handleConfirmAck(HandshakePacket packet) {
    setState(HANDSHAKE_COMPLETE);
  }

  @Override
  protected void handleConfirmOne(HandshakePacket packet) throws InvalidPacketException {
    ConfirmOnePacket confirmPacket = new ConfirmOnePacket(packet);

    confirmPacket.verifyMac(masterSecret.getResponderMacKey());
    confirmPacket.decrypt(masterSecret.getResponderZrtpKey());

    byte[] preimage = confirmPacket.getPreimage();
    foreignDH.veifyMac(preimage);

    setState(EXPECTING_CONFIRM_ACK);
    sendFreshPacket(new ConfirmTwoPacket(masterSecret.getInitiatorMacKey(),
                                         masterSecret.getInitiatorZrtpKey(),
                                         this.hashChain));
  }

  @Override
  protected void handleConfirmTwo(HandshakePacket packet) throws InvalidPacketException {
    throw new InvalidPacketException("Initiator received a Confirm2 packet?");
  }

  @Override
  protected void handleDH(HandshakePacket packet) throws InvalidPacketException {
    assert(localDH != null);
    foreignDH = new DHPartOnePacket(packet, true);

    if (Release.DEBUG)
      Log.w("ZRTPInitiatorSocket", "Got DH part 1...");

    byte[] h1 = foreignDH.getHash();
    byte[] h2 = calculateH2(h1);

    foreignHello.verifyMac(h2);

    SecretCalculator calculator = new SecretCalculator();
    byte[] dhResult     = calculator.calculateDHSecret(keyPair, foreignDH.getPvr());
    byte[] totalHash    = calculator.calculateTotalHash(foreignHello, commitPacket,
                                                        foreignDH, localDH);
    byte[] sharedSecret = calculator.calculateSharedSecret(dhResult, totalHash,
                                                           localHello.getZID(),
                                                           foreignHello.getZID());

    this.masterSecret   = new MasterSecret(sharedSecret, totalHash, localHello.getZID(),
                                           foreignHello.getZID());

    setState(EXPECTING_CONFIRM_ONE);
    sendFreshPacket(localDH);
  }

  @Override
  protected void handleHelloAck(HandshakePacket packet) throws InvalidPacketException {
    localDH      = new DHPartTwoPacket(hashChain, getPublicKey());
    commitPacket = new CommitPacket(hashChain, foreignHello.getMessageBytes(),
                                    localDH.getMessageBytes(), zid);

    setState(EXPECTING_DH_1);
    sendFreshPacket(commitPacket);
  }

  @Override
  protected void handleHello(HandshakePacket packet) throws InvalidPacketException {
    foreignHello = new HelloPacket(packet, true);
    localHello   = new HelloPacket(hashChain, zid);

    setState(EXPECTING_HELLO_ACK);
    sendFreshPacket(localHello);
  }

  private byte[] calculateH2(byte[] h1) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return md.digest(h1);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalArgumentException(e);
    }
  }

  @Override
  public void negotiate() throws NegotiationFailedException {
    super.negotiate();
  }


}
