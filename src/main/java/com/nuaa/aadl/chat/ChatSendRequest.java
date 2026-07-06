package com.nuaa.aadl.chat;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record ChatSendRequest(
    @NotBlank String conversationId,
    @NotBlank String module,
    @NotBlank String message,
    List<String> attachmentIds
) {
}
