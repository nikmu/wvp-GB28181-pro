package com.genersoft.iot.vmp.gb28181.bean;

import com.genersoft.iot.vmp.media.zlm.dto.MediaServerItem;

/**
 * title: BroadcastInfo
 * Description: 记录语音广播过程中相关信息
 *
 * @author zhujunjie
 * @date 2023/8/31 11:02
 */

public class BroadcastInfo {
    private String deviceId;
    private String channelId;
    private MediaServerItem mediaServerItem;

    public BroadcastInfo(String deviceId, String channelId, MediaServerItem mediaServerItem) {
        this.deviceId = deviceId;
        this.channelId = channelId;
        this.mediaServerItem = mediaServerItem;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getChannelId() {
        return channelId;
    }

    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    public MediaServerItem getMediaServerItem() {
        return mediaServerItem;
    }

    public void setMediaServerItem(MediaServerItem mediaServerItem) {
        this.mediaServerItem = mediaServerItem;
    }
}
