package com.genersoft.iot.vmp.gb28181.transmit.callback;

import com.genersoft.iot.vmp.media.zlm.dto.MediaServerItem;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * title: BroadcastInfoHolder
 * Description: <TODO description class purpose>
 *
 * @author zhujunjie
 * @date 2023/8/14 16:14
 */
@Component
public class BroadcastInfoHolder {
    private Map<String, Map<String, MediaServerItem>> map = new ConcurrentHashMap<>();

    public String getDeviceAudioChannelId(String deviceId) {
        Map<String, MediaServerItem> deviceMap = map.get(deviceId);
        if (deviceMap == null || deviceMap.size() == 0) {
            return null;
        }
        return deviceMap.keySet().stream().findFirst().get();
    }

    public MediaServerItem getMediaServerInfoByDeviceIdAndChannelId(String deviceId, String channelId) {
        Map<String, MediaServerItem> deviceMap = map.get(deviceId);
        if (deviceMap == null || ObjectUtils.isEmpty(channelId)) {
            return null;
        }
        return deviceMap.get(channelId);
    }

    public void setMediaServerInfo(String deviceId, String channelId, MediaServerItem mediaServerItem) {
        Map<String, MediaServerItem> deviceMap = map.get(deviceId);
        if (deviceMap == null) {
            deviceMap = new ConcurrentHashMap<>();
            map.put(deviceId, deviceMap);
        }
        deviceMap.put(channelId, mediaServerItem);

    }

    public void clearMediaServerInfo(String deviceId, String channelId) {
        Map<String, MediaServerItem> deviceMap = map.get(deviceId);
        if (deviceMap != null){
            deviceMap.remove(channelId);
        }
        if (deviceMap.size() == 0) {
            map.remove(deviceId);
        }
    }
}
