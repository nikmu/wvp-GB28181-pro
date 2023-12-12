package com.genersoft.iot.vmp.gb28181.transmit.event.response.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.genersoft.iot.vmp.gb28181.SipLayer;
import com.genersoft.iot.vmp.gb28181.bean.Gb28181Sdp;
import com.genersoft.iot.vmp.gb28181.bean.SendRtpItem;
import com.genersoft.iot.vmp.gb28181.transmit.SIPProcessorObserver;
import com.genersoft.iot.vmp.gb28181.transmit.SIPSender;
import com.genersoft.iot.vmp.gb28181.transmit.callback.DeferredResultHolder;
import com.genersoft.iot.vmp.gb28181.transmit.callback.RequestMessage;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.SIPRequestHeaderProvider;
import com.genersoft.iot.vmp.gb28181.transmit.event.response.SIPResponseProcessorAbstract;
import com.genersoft.iot.vmp.gb28181.utils.SipUtils;
import com.genersoft.iot.vmp.media.zlm.ZLMServerFactory;
import com.genersoft.iot.vmp.media.zlm.dto.MediaServerItem;
import com.genersoft.iot.vmp.service.IMediaServerService;
import com.genersoft.iot.vmp.storager.IRedisCatchStorage;
import com.genersoft.iot.vmp.vmanager.bean.WVPResult;
import gov.nist.javax.sip.ResponseEventExt;
import gov.nist.javax.sip.message.SIPResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sdp.*;
import javax.sip.InvalidArgumentException;
import javax.sip.ResponseEvent;
import javax.sip.SipException;
import javax.sip.SipFactory;
import javax.sip.address.SipURI;
import javax.sip.header.CallIdHeader;
import javax.sip.header.FromHeader;
import javax.sip.header.HeaderAddress;
import javax.sip.header.ToHeader;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;


/**
 * @description: 处理INVITE响应
 * @author: panlinlin
 * @date: 2021年11月5日 16：40
 */
@Component
public class InviteResponseProcessor extends SIPResponseProcessorAbstract {

	private final static Logger logger = LoggerFactory.getLogger(InviteResponseProcessor.class);
	private final String method = "INVITE";

	@Autowired
	private SIPProcessorObserver sipProcessorObserver;


	@Autowired
	private SipLayer sipLayer;

	@Autowired
	private SIPSender sipSender;

	@Autowired
	private SIPRequestHeaderProvider headerProvider;

	@Autowired
	private IRedisCatchStorage redisCatchStorage;

	@Autowired
	private IMediaServerService mediaServerService;

	@Autowired
	private ZLMServerFactory zlmServerFactory;

	@Autowired
	private DeferredResultHolder deferredResultHolder;


	@Override
	public void afterPropertiesSet() throws Exception {
		// 添加消息处理的订阅
		sipProcessorObserver.addResponseProcessor(method, this);
	}



	/**
	 * 处理invite响应
	 * 
	 * @param evt 响应消息
	 * @throws ParseException
	 */
	@Override
	public void process(ResponseEvent evt ){
		logger.debug("接收到消息：" + evt.getResponse());
		try {
			SIPResponse response = (SIPResponse)evt.getResponse();
			int statusCode = response.getStatusCode();
			// trying不会回复
			if (statusCode == Response.TRYING) {
			}
			// 成功响应
			// 下发ack
			if (statusCode == Response.OK) {
				ResponseEventExt event = (ResponseEventExt)evt;

				String contentString = new String(response.getRawContent());
				Gb28181Sdp gb28181Sdp = SipUtils.parseSDP(contentString);
				SessionDescription sdp = gb28181Sdp.getBaseSdb();
				String sessionName = sdp.getSessionName().getValue();
				SipURI requestUri = SipFactory.getInstance().createAddressFactory().createSipURI(sdp.getOrigin().getUsername(), event.getRemoteIpAddress() + ":" + event.getRemotePort());
				Request reqAck = headerProvider.createAckRequest(response.getLocalAddress().getHostAddress(), requestUri, response);

				logger.info("[回复ack] {}-> {}:{} ", sdp.getOrigin().getUsername(), event.getRemoteIpAddress(), event.getRemotePort());
				sipSender.transmitRequest( response.getLocalAddress().getHostAddress(), reqAck);
				if (sessionName.equalsIgnoreCase("Talk")) {
					//talk模式 摄像头回复
					String fromGbId = ((SipURI) ((HeaderAddress) evt.getResponse().getHeader(FromHeader.NAME)).getAddress().getURI()).getUser();
					String toGbId = ((SipURI) ((HeaderAddress) evt.getResponse().getHeader(ToHeader.NAME)).getAddress().getURI()).getUser();
					CallIdHeader callIdHeader = (CallIdHeader)evt.getResponse().getHeader(CallIdHeader.NAME);


					Vector mediaDescriptions = sdp.getMediaDescriptions(true);
					int port = -1;
					boolean mediaTransmissionTCP = false;
					Boolean tcpActive = null;
					for (int i = 0; i < mediaDescriptions.size(); i++) {
						MediaDescription mediaDescription = (MediaDescription) mediaDescriptions.get(i);
						Media media = mediaDescription.getMedia();

						Vector mediaFormats = media.getMediaFormats(false);
						if (mediaFormats.contains("8") || mediaFormats.contains("10")) {
							port = media.getMediaPort();
							String protocol = media.getProtocol();
							// 区分TCP发流还是udp， 当前默认udp
							if ("TCP/RTP/AVP".equals(protocol)) {
								String setup = mediaDescription.getAttribute("setup");
								if (setup != null) {
									mediaTransmissionTCP = true;
									if ("active".equals(setup)) {
										tcpActive = false; // 对方是主动，我们就是被动
									} else if ("passive".equals(setup)) {
										tcpActive = true;
									}
								}
							}
							break;
						}
					}

					SendRtpItem sendRtpItem =  redisCatchStorage.querySendRTPServer(fromGbId, null, null, callIdHeader.getCallId());
					if (sendRtpItem == null) {
						sendRtpItem =  redisCatchStorage.querySendRTPServer(toGbId, null, null, callIdHeader.getCallId());
					}
					if (sendRtpItem == null) {
						logger.warn("[收到invite响应]：未找到设备({})的音源信息", fromGbId);
						return;
					}
					redisCatchStorage.deleteSendRTPServer(sendRtpItem.getPlatformId(), null, sendRtpItem.getCallId(), null);
					sendRtpItem.setPort(port);
					sendRtpItem.setUsePs(true);
//					sendRtpItem.setPt(8);

					String is_Udp = sendRtpItem.isTcp() ? "0" : "1";
					MediaServerItem mediaInfo = mediaServerService.getOne(sendRtpItem.getMediaServerId());
					logger.info("收到invite，{}/{}开始向设备推流, 目标={}:{}，SSRC={}",sendRtpItem.getApp(), sendRtpItem.getStreamId(), sendRtpItem.getIp(), sendRtpItem.getPort(), sendRtpItem.getSsrc());
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
					param.put("recv_stream_id", "34020000001320000006");
					if (!sendRtpItem.isTcp()) {
						// 开启rtcp保活
						param.put("udp_rtcp_timeout", sendRtpItem.isRtcp()? "1":"0");
					}
					// TCP被动模式推流，先监听端口，收到连接后推流
					JSONObject jsonObject = zlmServerFactory.startSendRtpStreamForPassive(mediaInfo, param);
					if (jsonObject == null) {
						logger.error("RTP推流失败: 请检查ZLM服务");
					} else if (jsonObject.getInteger("code") == 0) {
						logger.info("调用ZLM推流接口, 结果： {}",  jsonObject);
						logger.info("RTP推流成功[ {}/{} ]，{}->{}:{}, " ,param.get("app"), param.get("stream"), jsonObject.getString("local_port"), param.get("dst_url"), param.get("dst_port"));
						String key = DeferredResultHolder.CALLBACK_CMD_TALK + sendRtpItem.getPlatformId() + sendRtpItem.getChannelId();
						RequestMessage msg = new RequestMessage();
						msg.setKey(key);
						msg.setData(WVPResult.success("语音通道已建立"));
						deferredResultHolder.invokeAllResult(msg);
					} else {
						logger.error("RTP推流失败: {}, 参数：{}",jsonObject.getString("msg"), JSON.toJSONString(param));
					}
				}
			}
		} catch (InvalidArgumentException | ParseException | SipException | SdpParseException e) {
			logger.info("[点播回复ACK]，异常：", e );
		} catch (SdpException e) {
			logger.error("[SDP解析异常]", e);
		}
	}

}
