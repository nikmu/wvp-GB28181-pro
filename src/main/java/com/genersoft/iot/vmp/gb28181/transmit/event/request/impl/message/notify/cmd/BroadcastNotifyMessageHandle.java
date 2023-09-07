package com.genersoft.iot.vmp.gb28181.transmit.event.request.impl.message.notify.cmd;

import com.alibaba.fastjson2.JSONObject;
import com.genersoft.iot.vmp.conf.DynamicTask;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.gb28181.bean.ParentPlatform;
import com.genersoft.iot.vmp.gb28181.bean.BroadcastInfoHolder;
import com.genersoft.iot.vmp.gb28181.transmit.callback.DeferredResultHolder;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.ISIPCommander;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.ISIPCommanderForPlatform;
import com.genersoft.iot.vmp.gb28181.transmit.event.request.SIPRequestProcessorParent;
import com.genersoft.iot.vmp.gb28181.transmit.event.request.impl.message.IMessageHandler;
import com.genersoft.iot.vmp.gb28181.transmit.event.request.impl.message.notify.NotifyMessageHandler;
import com.genersoft.iot.vmp.media.zlm.ZLMRESTfulUtils;
import com.genersoft.iot.vmp.media.zlm.dto.MediaServerItem;
import com.genersoft.iot.vmp.service.IMediaServerService;
import com.genersoft.iot.vmp.service.IPlayService;
import com.genersoft.iot.vmp.service.bean.SSRCInfo;
import com.genersoft.iot.vmp.storager.IVideoManagerStorage;
import gov.nist.javax.sip.message.SIPRequest;
import org.dom4j.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

import javax.sdp.*;
import javax.sip.InvalidArgumentException;
import javax.sip.RequestEvent;
import javax.sip.ResponseEvent;
import javax.sip.SipException;
import javax.sip.message.Response;
import java.text.ParseException;
import java.util.UUID;
import java.util.Vector;

import static com.genersoft.iot.vmp.gb28181.utils.XmlUtil.getText;

/**
 * title: BroadcastNotifyMessageHandle
 * Description: 语音广播事件处理
 *
 * @author zhujunjie
 * @date 2023/8/28 14:40
 */
@Component
public class BroadcastNotifyMessageHandle extends SIPRequestProcessorParent implements InitializingBean, IMessageHandler {
    private final Logger logger = LoggerFactory.getLogger(BroadcastNotifyMessageHandle.class);
    private final String cmdType = "Broadcast";

    @Autowired
    private NotifyMessageHandler notifyMessageHandler;

    @Autowired
    private ISIPCommanderForPlatform commanderForPlatform;

    @Autowired
    private ISIPCommander commander;

    @Autowired
    private IVideoManagerStorage storager;

    @Autowired
    private IMediaServerService mediaServerService;

    @Autowired
    private IPlayService playService;

    @Autowired
    private ZLMRESTfulUtils zlmresTfulUtils;

    @Autowired
    private DynamicTask dynamicTask;

    @Autowired
    private BroadcastInfoHolder broadcastInfoHolder;

    @Override
    public void afterPropertiesSet() throws Exception {
        notifyMessageHandler.addHandler(cmdType, this);
    }

    @Override
    public void handForDevice(RequestEvent evt, Device device, Element rootElement) {

    }

    @Override
    public void handForPlatform(RequestEvent evt, ParentPlatform parentPlatform, Element rootElement) {
        logger.info("收到来自平台[{}]的语音广播通知", parentPlatform.getServerGBId());
        // 回复200 OK
        try {
            responseAck((SIPRequest) evt.getRequest(), Response.OK);
        } catch (SipException | InvalidArgumentException | ParseException e) {
            logger.error("[命令发送失败] 国标级联 语音广播通知回复: {}", e.getMessage());
        }


        String channelId = getText(rootElement, "TargetID");
        String audioChannelId = getText(rootElement, "SourceID");
        String sn = getText(rootElement, "SN");

        Device platformOwnerDevice = storager.queryDeviceInfoByPlatformIdAndChannelId(parentPlatform.getServerGBId(), channelId);

        if (platformOwnerDevice ==null){
            logger.error("[平台没有该通道的使用权限]:platformId"+parentPlatform.getServerGBId()+"  deviceID:"+channelId);
            return;
        }
        Device device = storager.queryVideoDevice(platformOwnerDevice.getDeviceId());


        try {
            // 广播回应
            commanderForPlatform.audioBroadcastResponseCmd(parentPlatform, channelId, sn, null, eventResult -> {
                // 发送invite
                Integer tcpMode = 0;
                String streamMode = parentPlatform.getStreamMode().toUpperCase();
                if ("TCP-PASSIVE".equals(streamMode)) {
                    tcpMode = 1;
                } else if ("TCP-ACTIVE".equals(streamMode)) {
                    tcpMode = 2;
                } else if ("UDP".equals(streamMode)) {
                    tcpMode = 0;
                }
                MediaServerItem mediaServerItem = playService.getNewMediaServerItem(device);
                String localAudioChannelId = broadcastInfoHolder.getNewAudioChannel(device.getDeviceId(), channelId, mediaServerItem);
                String stream = localAudioChannelId;
                SSRCInfo ssrcInfo = mediaServerService.openRTPServer(mediaServerItem, stream, null, false,
                        false, 0, false, tcpMode); // 默认使用TCP主动方式
                try {
                    commanderForPlatform.audioInviteCmd(parentPlatform, audioChannelId, mediaServerItem, ssrcInfo, null, eventResult1 -> {
                        logger.info("收到INVITE 200OK");
                        // tcp主动，发起
                        if (parentPlatform.getStreamMode().equalsIgnoreCase("TCP-ACTIVE")) {
                            String connectKey = UUID.randomUUID().toString();
                            dynamicTask.startCron(connectKey, () -> {
                                ResponseEvent responseEvent = (ResponseEvent) eventResult1.event;
                                String contentString = new String(responseEvent.getResponse().getRawContent());
                                if (this.tcpActiveHandler(parentPlatform, audioChannelId, contentString, mediaServerItem, ssrcInfo)) {
                                    dynamicTask.stop(connectKey);

                                }
                            }, 200);
                            String delayConnectKey = UUID.randomUUID().toString();
                            dynamicTask.startDelay(delayConnectKey, () -> {
                                if(dynamicTask.contains(connectKey)) {
                                    dynamicTask.stop(connectKey);
                                    logger.error("[连接超时] TCP主动连接失败");
                                }
                            }, 5 * 1000);
                        }
                    }, (mediaServerItemInuse, hookParam ) -> {
                        // 收到推流
                        logger.info("收到stream_change订阅： " + hookParam);
                        try {
                            DeferredResult<String> result = new DeferredResult<>(3 * 1000L);
                            String key  = DeferredResultHolder.CALLBACK_CMD_BROADCAST + device.getDeviceId() + channelId;

                            commander.audioBroadcastCmd(device, localAudioChannelId, channelId, errorEvent -> {
                                //广播失败
                                broadcastInfoHolder.clearAudioChannel(localAudioChannelId);
                            });
                            String delaybroadcastCmd = UUID.randomUUID().toString();
//                                        dynamicTask.startDelay(de);
                        } catch (InvalidArgumentException | SipException | ParseException e) {
                            logger.error("[命令发送失败] 国标级联 语音广播: {}", e.getMessage());
                        }
                        logger.info("[收取语音流成功] platform: {}, channelId:{}", parentPlatform.getServerGBId(), channelId);
                    });
                } catch (InvalidArgumentException | SipException | ParseException e) {
                    logger.error("[命令发送失败] 国标级联 语音广播INVITE: {}", e.getMessage());
                }
            });
        } catch (InvalidArgumentException | SipException | ParseException e) {
            logger.error("[命令发送失败] 国标级联 语音广播通知应答: {}", e.getMessage());
        }


    }

    private boolean tcpActiveHandler(ParentPlatform parentPlatform, String channelId, String contentString,
                                  MediaServerItem mediaServerItem, SSRCInfo ssrcInfo){
        String substring = contentString.substring(0, contentString.indexOf("y="));
        try {
            SessionDescription sdp = SdpFactory.getInstance().createSessionDescription(substring);
            int port = -1;
            Vector mediaDescriptions = sdp.getMediaDescriptions(true);
            for (Object description : mediaDescriptions) {
                MediaDescription mediaDescription = (MediaDescription) description;
                Media media = mediaDescription.getMedia();

                Vector mediaFormats = media.getMediaFormats(false);
                if (mediaFormats.contains("96")) {
                    port = media.getMediaPort();
                    break;
                }
            }
            logger.info("[TCP主动连接对方] platformId: {}, channelId: {}, 连接对方的地址：{}:{}, 收流模式：{}, SSRC: {}", parentPlatform.getServerGBId(),
                    channelId, sdp.getConnection().getAddress(), port, parentPlatform.getStreamMode(), ssrcInfo.getSsrc());
            JSONObject jsonObject = zlmresTfulUtils.connectRtpServer(mediaServerItem, sdp.getConnection().getAddress(), port, ssrcInfo.getStream());
            logger.info("[TCP主动连接对方] 结果： {}", jsonObject);
            return jsonObject.getInteger("code").equals(0);
        } catch (SdpException e) {
            logger.error("[TCP主动连接对方] platformId: {}, channelId: {}, 解析200OK的SDP信息失败", parentPlatform.getServerGBId(), channelId, e);
            return false;
        }
    }

}
