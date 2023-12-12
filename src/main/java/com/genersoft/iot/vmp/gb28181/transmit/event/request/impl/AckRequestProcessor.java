package com.genersoft.iot.vmp.gb28181.transmit.event.request.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.genersoft.iot.vmp.conf.DynamicTask;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.conf.exception.SsrcTransactionNotFoundException;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.gb28181.bean.InviteStreamType;
import com.genersoft.iot.vmp.gb28181.bean.ParentPlatform;
import com.genersoft.iot.vmp.gb28181.bean.SendRtpItem;
import com.genersoft.iot.vmp.gb28181.transmit.SIPProcessorObserver;
import com.genersoft.iot.vmp.gb28181.transmit.callback.DeferredResultHolder;
import com.genersoft.iot.vmp.gb28181.transmit.callback.RequestMessage;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.ISIPCommander;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.ISIPCommanderForPlatform;
import com.genersoft.iot.vmp.gb28181.transmit.event.request.ISIPRequestProcessor;
import com.genersoft.iot.vmp.gb28181.transmit.event.request.SIPRequestProcessorParent;
import com.genersoft.iot.vmp.media.zlm.ZLMServerFactory;
import com.genersoft.iot.vmp.media.zlm.ZlmHttpHookSubscribe;
import com.genersoft.iot.vmp.media.zlm.dto.MediaServerItem;
import com.genersoft.iot.vmp.service.IMediaServerService;
import com.genersoft.iot.vmp.service.bean.MessageForPushChannel;
import com.genersoft.iot.vmp.service.bean.RequestPushStreamMsg;
import com.genersoft.iot.vmp.service.redisMsg.RedisGbPlayMsgListener;
import com.genersoft.iot.vmp.storager.IRedisCatchStorage;
import com.genersoft.iot.vmp.storager.IVideoManagerStorage;
import com.genersoft.iot.vmp.vmanager.bean.ErrorCode;
import com.genersoft.iot.vmp.vmanager.bean.WVPResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sip.InvalidArgumentException;
import javax.sip.RequestEvent;
import javax.sip.SipException;
import javax.sip.address.SipURI;
import javax.sip.header.CallIdHeader;
import javax.sip.header.FromHeader;
import javax.sip.header.HeaderAddress;
import javax.sip.header.ToHeader;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;

/**
 * SIP命令类型： ACK请求
 */
@Component
public class AckRequestProcessor extends SIPRequestProcessorParent implements InitializingBean, ISIPRequestProcessor {

	private Logger logger = LoggerFactory.getLogger(AckRequestProcessor.class);
	private final String method = "ACK";

	@Autowired
	private SIPProcessorObserver sipProcessorObserver;

	@Override
	public void afterPropertiesSet() throws Exception {
		// 添加消息处理的订阅
		sipProcessorObserver.addRequestProcessor(method, this);
	}

	@Autowired
    private IRedisCatchStorage redisCatchStorage;

	@Autowired
    private UserSetting userSetting;

	@Autowired
	private IVideoManagerStorage storager;

	@Autowired
	private ZLMServerFactory zlmServerFactory;

	@Autowired
	private ZlmHttpHookSubscribe hookSubscribe;

	@Autowired
	private IMediaServerService mediaServerService;

	@Autowired
	private ZlmHttpHookSubscribe subscribe;

	@Autowired
	private DynamicTask dynamicTask;

	@Autowired
	private ISIPCommander cmder;

	@Autowired
	private ISIPCommanderForPlatform commanderForPlatform;

	@Autowired
	private RedisGbPlayMsgListener redisGbPlayMsgListener;

	@Autowired
	private DeferredResultHolder deferredResultHolder;
	/**   
	 * 处理  ACK请求
	 * 
	 * @param evt
	 */
	@Override
	public void process(RequestEvent evt) {
		CallIdHeader callIdHeader = (CallIdHeader)evt.getRequest().getHeader(CallIdHeader.NAME);

		String fromGbId = ((SipURI) ((HeaderAddress) evt.getRequest().getHeader(FromHeader.NAME)).getAddress().getURI()).getUser();
		logger.info("[收到ACK]： fromGbId->{}", fromGbId);
		// 取消设置的超时任务
		dynamicTask.stop(callIdHeader.getCallId());
		ParentPlatform parentPlatform = storager.queryParentPlatByServerGBId(fromGbId);
		if (parentPlatform != null) { // 上级平台回复的ACK
			String channelId = ((SipURI) ((HeaderAddress) evt.getRequest().getHeader(ToHeader.NAME)).getAddress().getURI()).getUser();
			SendRtpItem sendRtpItem =  redisCatchStorage.querySendRTPServer(fromGbId, channelId, null, callIdHeader.getCallId());
			if (sendRtpItem == null) {
				logger.warn("[收到ACK]：未找到通道({})的推流信息", channelId);
				return;
			}
			// tcp主动时，此时是级联下级平台，在回复200ok时，本地已经请求zlm开启监听，跳过下面步骤
			if (sendRtpItem.isTcpActive()) {
				logger.info("收到ACK，rtp/{} TCP主动方式后续处理", sendRtpItem.getStreamId());
				return;
			}
			String is_Udp = sendRtpItem.isTcp() ? "0" : "1";
			MediaServerItem mediaInfo = mediaServerService.getOne(sendRtpItem.getMediaServerId());
			logger.info("收到ACK，rtp/{}开始向上级推流, 目标={}:{}，SSRC={}", sendRtpItem.getStreamId(), sendRtpItem.getIp(), sendRtpItem.getPort(), sendRtpItem.getSsrc());
			Map<String, Object> param = new HashMap<>(12);
			param.put("vhost","__defaultVhost__");
			param.put("app",sendRtpItem.getApp());
			param.put("stream",sendRtpItem.getStreamId());
			param.put("ssrc", sendRtpItem.getSsrc());
			param.put("dst_url",sendRtpItem.getIp());
			param.put("dst_port", sendRtpItem.getPort());
			param.put("is_udp", is_Udp);
			param.put("src_port", sendRtpItem.getLocalPort());
			param.put("pt", sendRtpItem.getPt());
			param.put("use_ps", sendRtpItem.isUsePs() ? "1" : "0");
			param.put("only_audio", sendRtpItem.isOnlyAudio() ? "1" : "0");
			if (!sendRtpItem.isTcp()) {
				// 开启rtcp保活
				param.put("udp_rtcp_timeout", sendRtpItem.isRtcp()? "1":"0");
			}

			if (mediaInfo == null) {
				RequestPushStreamMsg requestPushStreamMsg = RequestPushStreamMsg.getInstance(
						sendRtpItem.getMediaServerId(), sendRtpItem.getApp(), sendRtpItem.getStreamId(),
						sendRtpItem.getIp(), sendRtpItem.getPort(), sendRtpItem.getSsrc(), sendRtpItem.isTcp(),
						sendRtpItem.getLocalPort(), sendRtpItem.getPt(), sendRtpItem.isUsePs(), sendRtpItem.isOnlyAudio());
				redisGbPlayMsgListener.sendMsgForStartSendRtpStream(sendRtpItem.getServerId(), requestPushStreamMsg, jsonObject->{
					startSendRtpStreamHand(evt, sendRtpItem, parentPlatform, null, jsonObject, param, callIdHeader);
				});
			}else {
				JSONObject startSendRtpStreamResult = zlmServerFactory.startSendRtpStream(mediaInfo, param);
				if (startSendRtpStreamResult != null) {
					startSendRtpStreamHand(evt, sendRtpItem, parentPlatform, null, startSendRtpStreamResult, param, callIdHeader);
				}
			}
		} else {
			// 下级回复的ACK
			Device device = storager.queryVideoDevice(fromGbId);
			String channelId = ((SipURI) ((HeaderAddress) evt.getRequest().getHeader(ToHeader.NAME)).getAddress().getURI()).getUser();
			SendRtpItem sendRtpItem =  redisCatchStorage.querySendRTPServer(fromGbId, null, null, callIdHeader.getCallId());
			if (sendRtpItem == null) {
				logger.warn("[收到ACK]：未找到设备({})的音源信息", device.getDeviceId());
				return;
			}
			String is_Udp = sendRtpItem.isTcp() ? "0" : "1";
			MediaServerItem mediaInfo = mediaServerService.getOne(sendRtpItem.getMediaServerId());
			logger.info("收到ACK，{}/{}开始向设备推流, 目标={}:{}，SSRC={}",sendRtpItem.getApp(), sendRtpItem.getStreamId(), sendRtpItem.getIp(), sendRtpItem.getPort(), sendRtpItem.getSsrc());
			Map<String, Object> param = new HashMap<>(12);
			param.put("vhost","__defaultVhost__");
			param.put("app",sendRtpItem.getApp());
			param.put("stream",sendRtpItem.getStreamId());
			param.put("ssrc", sendRtpItem.getSsrc());
			param.put("dst_url",sendRtpItem.getIp());
			param.put("dst_port", sendRtpItem.getPort());
			param.put("is_udp", is_Udp);
			param.put("src_port", sendRtpItem.getLocalPort());
			param.put("pt", sendRtpItem.getPt());
			param.put("use_ps", sendRtpItem.isUsePs() ? "1" : "0");
			param.put("only_audio", sendRtpItem.isOnlyAudio() ? "1" : "0");
			if (!sendRtpItem.isTcp()) {
				// 开启rtcp保活
				param.put("udp_rtcp_timeout", sendRtpItem.isRtcp()? "1":"0");
			}
			JSONObject startSendRtpStreamResult = null;
			if (sendRtpItem.isTcp() && !sendRtpItem.isTcpActive()) {
				// TCP被动模式推流，先监听端口，收到连接后推流
				startSendRtpStreamResult = zlmServerFactory.startSendRtpStreamForPassive(mediaInfo, param);
			} else {
				startSendRtpStreamResult = zlmServerFactory.startSendRtpStream(mediaInfo, param);
			}
			if (startSendRtpStreamResult != null) {
				startSendRtpStreamHand(evt, sendRtpItem, null, device, startSendRtpStreamResult, param, callIdHeader);
			}

		}
	}
	private void startSendRtpStreamHand(RequestEvent evt, SendRtpItem sendRtpItem, ParentPlatform parentPlatform, Device device,
										JSONObject jsonObject, Map<String, Object> param, CallIdHeader callIdHeader) {
		if (jsonObject == null) {
			logger.error("RTP推流失败: 请检查ZLM服务");
		} else if (jsonObject.getInteger("code") == 0) {
			logger.info("调用ZLM推流接口, 结果： {}",  jsonObject);
			logger.info("RTP推流成功[ {}/{} ]，{}->{}:{}, " ,param.get("app"), param.get("stream"), jsonObject.getString("local_port"), param.get("dst_url"), param.get("dst_port"));
			if (sendRtpItem.getPlayType() == InviteStreamType.PUSH) {
				MessageForPushChannel messageForPushChannel = MessageForPushChannel.getInstance(0, sendRtpItem.getApp(), sendRtpItem.getStreamId(),
						sendRtpItem.getChannelId(), parentPlatform.getServerGBId(), parentPlatform.getName(), userSetting.getServerId(),
						sendRtpItem.getMediaServerId());
				messageForPushChannel.setPlatFormIndex(parentPlatform.getId());
				redisCatchStorage.sendPlatformStartPlayMsg(messageForPushChannel);
			}
//			if ("137".equals(sendRtpItem.getChannelId().substring(10,13))){
				// 推语音广播
				String key = DeferredResultHolder.CALLBACK_CMD_BROADCAST + device.getDeviceId() + sendRtpItem.getChannelId();
				RequestMessage msg = new RequestMessage();
            	msg.setKey(key);
				msg.setData(WVPResult.success("语音通道已建立"));
				deferredResultHolder.invokeAllResult(msg);
//			}

		} else {
			logger.error("RTP推流失败: {}, 参数：{}",jsonObject.getString("msg"), JSON.toJSONString(param));
			try {
				if (sendRtpItem.isOnlyAudio()) {
					if ("137".equals(sendRtpItem.getChannelId().substring(10,13))) {
						// 语音广播推送失败
						String key = DeferredResultHolder.CALLBACK_CMD_BROADCAST + device.getDeviceId() + sendRtpItem.getChannelId();
						RequestMessage msg = new RequestMessage();
						msg.setKey(key);
						msg.setData(WVPResult.fail(ErrorCode.ERROR100.getCode(), String.format("语音广播操作失败，错误码： %s", jsonObject.getString("msg"))));
						deferredResultHolder.invokeAllResult(msg);
//						cmder.audioStreamByeCmd(device, null, callIdHeader.getCallId(), null);
					}
				}else {
					// 向上级平台
					commanderForPlatform.streamByeCmd(parentPlatform, callIdHeader.getCallId());
				}
			} catch (SipException | InvalidArgumentException | ParseException e) {
				logger.error("[命令发送失败] 国标级联 发送BYE: {}", e.getMessage());
			}
		}
	}
}
