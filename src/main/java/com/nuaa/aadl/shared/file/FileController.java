package com.nuaa.aadl.shared.file;

import java.io.IOException;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/files")
public class FileController {

  private final FileService fileService;
  public String filePath = "";

  public FileController(FileService fileService) {
    this.fileService = fileService;
  }


  @PostMapping("/upload")
  public UploadFilesResponse upload(@RequestParam("files") List<MultipartFile> files, @RequestParam("path") String path) throws IOException {
    System.out.println("从前端收到文件2");
    System.out.println(path);
    this.filePath = path;
    return fileService.upload(files);
  }

  @GetMapping("/getPath")
  public ResponseEntity<String> getFilePath() {
    return ResponseEntity.ok(filePath);
  }

  @GetMapping("/{fileId}")
  public ResponseEntity<FileContentResponse> getFile(@PathVariable String fileId) {
    return fileService.getFileContent(fileId)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
