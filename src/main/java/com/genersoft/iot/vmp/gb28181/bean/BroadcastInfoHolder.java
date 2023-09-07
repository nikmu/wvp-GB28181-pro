package com.genersoft.iot.vmp.gb28181.bean;

import com.genersoft.iot.vmp.conf.SipConfig;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.media.zlm.dto.MediaServerItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * title: BroadcastInfoHolder
 * Description: 记录语音广播过程中相关信息
 *
 * @author zhujunjie
 * @date 2023/8/14 16:14
 */
@Component
public class BroadcastInfoHolder {

    /**
     * 语音对讲最大并发个数
     */
    private final static Integer MAX_AUDIO_CHANNEL = 10000;

    /**
     * 语音对讲最大并发个数
     */
    private static final String AUDIO_INFO_KEY = "VMP_AUDIO_INFO_";

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private SipConfig sipConfig;

    @Autowired
    private UserSetting userSetting;

    private Map<String, BroadcastInfo> map = new ConcurrentHashMap<>();

    public void initMediaServerAudioChannelId(String mediaServerId, Set<String> usedSet) {
        String channelPrefix = sipConfig.getDomain();
        String redisKey = AUDIO_INFO_KEY + userSetting.getServerId() + "_" + mediaServerId;
        List<String> channelList = new ArrayList<>();
        for (int i = 1; i < MAX_AUDIO_CHANNEL; i++) {
            String channelId = String.format("%s1367%06d", channelPrefix, i);

            if (null == usedSet || !usedSet.contains(channelId)) {
                channelList.add(channelId);

            }
        }
        if (redisTemplate.opsForSet().size(redisKey) != null) {
            redisTemplate.delete(redisKey);
        }
        redisTemplate.opsForSet().add(redisKey, channelList.toArray(new String[0]));
    }

    /**
     * 获取后四位数SN,随机数
     */
    private String getSN(String mediaServerId) {
        String sn = null;
        String redisKey = AUDIO_INFO_KEY + userSetting.getServerId() + "_" + mediaServerId;
        Long size = redisTemplate.opsForSet().size(redisKey);
        if (size == null || size == 0) {
            throw new RuntimeException("audioChannel已经用完");
        } else {
            // 在集合中移除并返回一个随机成员。
            sn = (String) redisTemplate.opsForSet().pop(redisKey);
            redisTemplate.opsForSet().remove(redisKey, sn);
        }
        return sn;
    }

    /**
     * 释放channel，主要用完的channel一定要释放，否则会耗尽
     *
     * @param audioChannel 需要重置的audioChannel
     */
    public void releaseSsrc(String mediaServerId, String audioChannel) {
        if (audioChannel == null) {
            return;
        }
        String redisKey = AUDIO_INFO_KEY + userSetting.getServerId() + "_" + mediaServerId;
        redisTemplate.opsForSet().add(redisKey, audioChannel);
    }

    /**
     * 获取到一个闲置的audioChannelId
     * @param mediaServerId
     * @return
     */
    public String getAudioChannelId(String mediaServerId) {
        return getSN(mediaServerId);
    }

    /**
     * 重置一个流媒体服务的所有audioChannel
     *
     * @param mediaServerId 流媒体服务ID
     */
    public void reset(String mediaServerId) {
        this.initMediaServerAudioChannelId(mediaServerId, null);
    }

    /**
     * 是否已经存在了某个MediaServer的SSRC信息
     *
     * @param mediaServerId 流媒体服务ID
     */
    public boolean hasMediaServerAudioChannel(String mediaServerId) {
        String redisKey = AUDIO_INFO_KEY + userSetting.getServerId() + "_" + mediaServerId;
        return redisTemplate.opsForSet().members(redisKey) != null;
    }


    public String getNewAudioChannel(String deviceId, String channelId, MediaServerItem mediaServerItem) {
        String audioChannelId = this.getAudioChannelId(mediaServerItem.getId());
        BroadcastInfo broadcastInfo = new BroadcastInfo(deviceId, channelId, mediaServerItem);
        map.put(audioChannelId, broadcastInfo);
        return audioChannelId;
    }

    public BroadcastInfo getBroadcastInfo(String audioChannelId){
        return map.get(audioChannelId);
    }

    public void clearAudioChannel(String audioChannelId){
        map.remove(audioChannelId);
    }

}
