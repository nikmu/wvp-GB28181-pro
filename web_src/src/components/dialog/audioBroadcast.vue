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
      audioChannelId: ''
    }
  },
  mounted() {
    console.log(userService.getUser());
  },
  methods: {
    async call() {
      await this.getRtcUrl();
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
          that.loading = false
          that.calling = false
          that.$message({
            showClose: true,
            message: 'ICE 协商出错',
            type: 'warning',
          });
      });

      webrtcPusher.on(ZLMRTCClient.Events.WEBRTC_OFFER_ANWSER_EXCHANGE_FAILED,function(e)
      {// offer anwser 交换失败
          console.log('offer anwser 交换失败',e)
          that.loading = false
          that.calling = false
          that.$message({
            showClose: true,
            message: 'offer anwser 交换失败',
            type: 'warning',
          });
          stop();
      });

      webrtcPusher.on(ZLMRTCClient.Events.WEBRTC_ON_CONNECTION_STATE_CHANGE,function(state)
      {// RTC 状态变化 ,详情参考 https://developer.mozilla.org/en-US/docs/Web/API/RTCPeerConnection/connectionState
        console.log('当前状态==>',state)
        if(state === 'connected'){
          that.boadcast()
        }
      });

    },
    hangUp() {
      this.loading = true
      this.$axios({
        method: 'get',
        url:`/api/play/broadcast/stop/${this.deviceId}/${this.channelId}?audioChannelId=${this.audioChannelId}`,
      }).then(res => {
        this.loading = false
        if(res.data.code === 0){
          this.calling = false
        }
        if(webrtcPusher)
        {
          webrtcPusher.close();
          webrtcPusher = null;
        }
      })
    },
    boadcast() {
      this.$axios({
        method: 'get',
        url:`/api/play/broadcast/${this.deviceId}/${this.channelId}?audioChannelId=${this.audioChannelId}`,
      }).then(res => {
        this.loading = false
        if(res.data.code === 0){
          this.calling = true
        } else {
          this.calling = false
          this.$message({
            showClose: true,
            message: res.data.msg,
            type: 'warning',
          });
          this.hangUp()
        }
      })
    },
    async getRtcUrl() {
      const res = await this.$axios({
        method: 'get',
        url:`/api/v1/stream/pushRtc/${this.deviceId}/${this.channelId}`,
      });
      if (location.protocol === "https:") {
          this.webrtcUrl = res.data.pushRtcs + "&sign=" + crypto.createHash('md5').update(userService.getUser().pushKey, "utf8").digest('hex')
        }else {
          this.webrtcUrl = res.data.pushRtc + "&sign=" + crypto.createHash('md5').update(userService.getUser().pushKey, "utf8").digest('hex')
        }
      this.audioChannelId = res.data.audioChannelId
      console.log(this.webrtcUrl)
      return this.audioChannelId
    }
  }
}
</script>