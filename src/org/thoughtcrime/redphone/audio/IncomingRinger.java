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

package org.thoughtcrime.redphone.audio;

import android.content.Context;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Vibrator;
import android.util.Log;

/**
 * Plays the 'incoming call' ringtone and manages the audio player state associated with this
 * process.
 *
 * @author Stuart O. Anderson
 */
public class IncomingRinger {

  private static final long[] VIBRATE_PATTERN = {0, 1000, 1000};

  private final Context context;
  private final Ringer ringer;
  private final Vibrator vibrator;

  public IncomingRinger(Context context) {
    this.context  = context;
    Uri rtURI = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
    Ringtone ringtone = RingtoneManager.getRingtone(context, rtURI);
    this.vibrator = (Vibrator)context.getSystemService(Context.VIBRATOR_SERVICE);

    if( ringtone != null ) {
      ringer = new Ringer( ringtone );
    } else {
      Log.e("IncomingRinger", "Couldn't find a ringtone for URI: " + rtURI.toString() );
      ringer = null;
    }
  }

  public void start() {
    AudioManager audioManager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);

    int ringerMode            = audioManager.getRingerMode();
    int vibrateSetting        = audioManager.getVibrateSetting(AudioManager.VIBRATE_TYPE_RINGER);

    //TODO request audio gain here
    //audioManager).requestAudioFocus( )

    if (ringerMode == AudioManager.RINGER_MODE_VIBRATE ||
       (ringerMode == AudioManager.RINGER_MODE_NORMAL && vibrateSetting == AudioManager.VIBRATE_SETTING_ON)) {
      Log.w("IncomingRinger", "Starting Vibration");
      vibrator.vibrate(VIBRATE_PATTERN, 1);
    }

    if (ringer != null && ringerMode == AudioManager.RINGER_MODE_NORMAL ) {
      //audioManager.setStreamVolume(AudioManager.STREAM_RING, audioManager.getStreamMaxVolume(AudioManager.STREAM_RING),0);
      audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
      audioManager.setMode(AudioManager.MODE_RINGTONE);
      ringer.start();
    } else {
      Log.w("IncomingRinger", ringer + " mode: " + ringerMode);
    }

  }

  public void stop() {
    if( ringer != null ) {
      ringer.stop();
    }
    Log.d("IncomingRinger", "Cancelling vibrator" );
    vibrator.cancel();
  }

  private class Ringer {
    private final Ringtone myTone;
    private volatile boolean terminate = false;

    public Ringer( Ringtone tone ) {
      myTone = tone;
    }

    public synchronized void start(){
      terminate = false;
      Thread thread = new Thread(new Runnable() {
        @Override
        public void run() {
          while(true) {
            if(terminate) {
              myTone.stop();
              Log.d( "Ringer", "Done playing...");
              return;
            }
            if (!myTone.isPlaying()) {
              Log.d( "Ringer", "Playing..." );
              myTone.play();
            }
            try {
              synchronized (Ringer.this) {
                Ringer.this.wait(500);
              }
            } catch (InterruptedException e) {
            }
          }
        }
      });
      thread.setName("Ringer");
      thread.start();
    }

    public synchronized void stop() {
      terminate = true;
      notify();
    }
  }
}