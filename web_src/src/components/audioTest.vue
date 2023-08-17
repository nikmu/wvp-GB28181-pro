<template>
  <div class="play-audio">
    <el-button @click="send()" ref="start">开始对讲</el-button>
    <el-button @click="stopCall" ref="stop">结束对讲</el-button>
  </div>
</template>

<script>
import userService from "./service/UserService"
export default {
  data() {
    return {
      websock: null,
      mediaStack: null,
      audioCtx: null,
      scriptNode: null,
      source: null,
      play: true
    }
  },
  mounted() {
    this.initWebSocket()
  },
  destroyed() { // 离开页面生命周期函数
    this.websocketclose();
  },
  methods: {
    // 开始对讲
    send() {
      this.play = true
      this.audioCtx = new AudioContext()
      this.scriptNode = this.audioCtx.createScriptProcessor(4096, 1, 1)
      navigator.mediaDevices.getUserMedia({ audio: true }).then(stream => {
        this.mediaStack = stream
        this.source = this.audioCtx.createMediaStreamSource(stream)
        this.source.connect(this.scriptNode)
        this.scriptNode.connect(this.audioCtx.destination)
      }).catch(function (err) {
        /* 处理error */
        console.log('err', err)
      })

      // 当麦克风有声音输入时，会调用此事件
      // 实际上麦克风始终处于打开状态时，即使不说话，此事件也在一直调用
      this.scriptNode.onaudioprocess = (audioProcessingEvent) => {
        const inputBuffer = audioProcessingEvent.inputBuffer
        // 由于只创建了一个音轨，这里只取第一个频道的数据
        const inputData = inputBuffer.getChannelData(0)
        // 通过socket传输数据，实际上传输的是Float32Array
        if (this.websock.readyState === 1) {
			    console.log("发送的数据",inputData);
          this.websock.send(inputData)
        }
      }
    },
    // 关闭麦克风
    stopCall() {
      console.log("222")
      this.play = false
      this.mediaStack.getTracks()[0].stop()
      this.scriptNode.disconnect()
    },
    initWebSocket: function () { // 建立连接
      // WebSocket与普通的请求所用协议有所不同，ws等同于http，wss等同于https
      // var userId = store.getters.userInfo.id;
      var url = "ws://192.168.10.17:18080/websocket/audio";
      this.websock = new WebSocket(url, [userService.getToken()]);
      this.websock.binaryType = 'arraybuffer'
      this.websock.onopen = this.websocketonopen;
      this.websock.onerror = this.websocketonerror;
      this.websock.onmessage = this.websocketonmessage;
      this.websock.onclose = this.websocketclose;
    },
    // 连接成功后调用
    websocketonopen: function () {
      console.log("WebSocket连接成功");
    },
    // 发生错误时调用
    websocketonerror: function (e) {
      console.log("WebSocket连接发生错误");
    },
    // 给后端发消息时调用
    websocketsend: function (e) {
      console.log("WebSocket连接发生错误");
    },
    // 接收后端消息
    // vue 客户端根据返回的cmd类型处理不同的业务响应
    websocketonmessage: function (e) {
      var data = eval("(" + e.data + ")"); 
        //处理订阅信息
      if(data.cmd == "topic"){
          //TODO 系统通知
    
      }else if(data.cmd == "user"){
          //TODO 用户消息

      }
    },
    // 关闭连接时调用
    websocketclose: function (e) {
      console.log("connection closed (" + e.code + ")");
    },
  }
}
</script>