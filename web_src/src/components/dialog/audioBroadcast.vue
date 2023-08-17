<template>
  <div>
    <video id='webRtcPusherBox' controls autoplay style="display: none;">
      Your browser is too old which doesn't support HTML5 video.
    </video>
    <el-button :loading="loading" circle :icon="calling ? 'el-icon-turn-off-microphone':'el-icon-microphone'" :type="calling ? 'danger':'success'" size="max" @click="calling ? hangUp() : call()"></el-button>
  </div>
</template>
<script>
import userService from "../service/UserService";
import crypto from 'crypto'

let webrtcPusher = null;

export default {
  name: 'audioBroadcast',
  props: ['deviceId', 'channelId', 'error'],
  data() {
    return {
      loading: false,
      calling: false,
      webrtcUrl: '',
      mediaId: ''
    }
  },
  mounted() {
    this.getRtcUrl();
    console.log(userService.getUser());
  },
  methods: {
    call() {
      let that = this
      this.loading = true
      webrtcPusher = new ZLMRTCClient.Endpoint({
                element: document.getElementById('webRtcPusherBox'),// video 标签
                debug: true,// 是否打印日志
                zlmsdpUrl: this.webrtcUrl,//流地址
                simulecast: false,
                useCamera: false,
                audioEnable: true,
                videoEnable: false,
                recvOnly: false,
            })
      webrtcPusher.on(ZLMRTCClient.Events.WEBRTC_ICE_CANDIDATE_ERROR,function(e)
      {// ICE 协商出错
          console.log('ICE 协商出错')
      });

      webrtcPusher.on(ZLMRTCClient.Events.WEBRTC_OFFER_ANWSER_EXCHANGE_FAILED,function(e)
      {// offer anwser 交换失败
          console.log('offer anwser 交换失败',e)
          stop();
      });

      webrtcPusher.on(ZLMRTCClient.Events.WEBRTC_ON_CONNECTION_STATE_CHANGE,function(state)
      {// RTC 状态变化 ,详情参考 https://developer.mozilla.org/en-US/docs/Web/API/RTCPeerConnection/connectionState
        console.log('当前状态==>',state)
        if(state === 'connected'){
          that.boadcast()
        } else {
          that.calling = false
        }
      });

    },
    hangUp() {
      this.loading = true
      this.$axios({
        method: 'get',
        url:`/api/play/broadcast/stop/${this.deviceId}/${this.channelId}`,
      }).then(res => {
        if(res.data.code === 0){
          this.calling = false
          this.loading = false
        }
      })
    },
    boadcast() {
      this.$axios({
        method: 'get',
        url:`/api/play/broadcast/${this.deviceId}/${this.channelId}?mediaServerId=${this.mediaId}`,
      }).then(res => {
        if(res.data.code === 0){
          this.calling = true
          this.loading = false
        }
      })
    },
    getRtcUrl() {
      this.$axios({
        method: 'get',
        url:`/api/v1/stream/pushRtc/${this.deviceId}/${this.channelId}`,
      }).then(res => {
        console.log('111111', res)
        if (location.protocol === "https:") {
          this.webrtcUrl = res.data.pushRtcs + "&sign=" + crypto.createHash('md5').update(userService.getUser().pushKey, "utf8").digest('hex')
        }else {
          this.webrtcUrl = res.data.pushRtc + "&sign=" + crypto.createHash('md5').update(userService.getUser().pushKey, "utf8").digest('hex')
        }
        this.mediaId = res.data.mediaId
        console.log(this.webrtcUrl)
      })
    }
  }
}
</script>