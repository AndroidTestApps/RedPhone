#ifndef __WEBRTC_JITTER_BUFFER_H__
#define __WEBRTC_JITTER_BUFFER_H__

#include "AudioCodec.h"
#include "WebRtcCodec.h"
#include "RtpPacket.h"

#include <android/log.h>
#include <pthread.h>
#include <unistd.h>

#include <modules/audio_coding/neteq/interface/neteq.h>
#include <modules/interface/module_common_types.h>

class WebRtcJitterBuffer {

private:
  webrtc::NetEq *neteq;
  WebRtcCodec webRtcCodec;
  volatile int running;

public:
  WebRtcJitterBuffer(AudioCodec &codec);
  int init();

  // TODO destrucotor?

  void addAudio(RtpPacket *packet);
  int getAudio(short *rawData, int maxRawData);
  void stop();
  void collectStats();
  static void* collectStats(void *context);
};



#endif