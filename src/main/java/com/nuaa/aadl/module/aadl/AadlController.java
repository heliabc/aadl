package com.nuaa.aadl.module.aadl;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.Map;

@RestController
@RequestMapping("/api/modules/aadl")
public class AadlController {

  private final AadlModelService aadlModelService;

  public AadlController(AadlModelService aadlModelService) {
    this.aadlModelService = aadlModelService;
  }

  @GetMapping("/download")
  public ResponseEntity<String> download(@RequestParam String conversationId) {
    String content = aadlModelService.getAadl(conversationId);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("text/plain; charset=utf-8"))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"aadl-model.aadl\"")
        .body(content);
  }

//   @PostMapping("/upload")
//  public ResponseEntity<Map<String, List<Map<String, String>>>> uploadAadlFile(
//         @RequestParam("file") MultipartFile file,
//         @RequestParam String conversationId) {
//     List<String> fileIds = aadlModelService.uploadAadlFile(file, conversationId);
//     List<Map<String, String>> files = fileIds.stream()
//         .map(id -> Map.of("id", id, "name", "uploaded.aadl"))  // name 可以从原文件名获取，但这里简化
//         .collect(Collectors.toList());
//     return ResponseEntity.ok(Map.of("files", files));
// }

// @PostMapping("/api/files/upload")
// public ResponseEntity<Map<String, List<Map<String, String>>>> uploadFiles(
  
//         @RequestParam("files") MultipartFile[] files,
//         @RequestParam("conversationId") String conversationId) {
//     List<Map<String, String>> allFiles = new ArrayList<>();
//     for (MultipartFile file : files) {
//         List<Map<String, String>> infos = aadlModelService.uploadAadlFileWithInfo(file, conversationId);
//         allFiles.addAll(infos);
//     }
//       System.out.println("从前端收到文件");
//     return ResponseEntity.ok(Map.of("files", allFiles));
// }

@PostMapping("/generate-from-requirement")
public Map<String, String> generateFromRequirement(@RequestBody Map<String, String> payload) {
    String conversationId = payload.get("conversationId");
    String requirementText = payload.get("requirementText");
    if (conversationId == null || conversationId.isBlank()) {
        throw new IllegalArgumentException("conversationId cannot be empty");
    }
    if (requirementText == null || requirementText.isBlank()) {
        throw new IllegalArgumentException("requirementText cannot be empty");
    }
    String aadlModel = aadlModelService.generateAndPersistFromRequirement(conversationId, requirementText);
    return Map.of("aadlModel", aadlModel);
}

@PostMapping(path = "/generate-from-requirement/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter generateFromRequirementStream(@RequestBody Map<String, String> payload, HttpServletResponse response) {
    String conversationId = payload.get("conversationId");
    String requirementText = payload.get("requirementText");
    if (conversationId == null || conversationId.isBlank()) {
        throw new IllegalArgumentException("conversationId cannot be empty");
    }
    if (requirementText == null || requirementText.isBlank()) {
        throw new IllegalArgumentException("requirementText cannot be empty");
    }

    response.setHeader("Cache-Control", "no-cache, no-transform");
    response.setHeader("Connection", "keep-alive");
    response.setHeader("X-Accel-Buffering", "no");

    SseEmitter emitter = new SseEmitter(600_000L);
    aadlModelService.generateFromRequirementStream(conversationId, requirementText, emitter);
    return emitter;
}

}
