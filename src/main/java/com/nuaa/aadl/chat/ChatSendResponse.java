package com.nuaa.aadl.chat;

import java.util.Map;

public record ChatSendResponse(
    String reply,
    Map<String, Object> moduleData
) {
}
