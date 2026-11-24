package com.example.jobpuzzle.document.controller;

import com.example.jobpuzzle.document.dto.DirectDocumentRegisterRequest;
import com.example.jobpuzzle.document.dto.DocumentDetailResponse;
import com.example.jobpuzzle.document.dto.DocumentListResponse;
import com.example.jobpuzzle.document.dto.DocumentNameUpdateRequest;
import com.example.jobpuzzle.document.dto.DocumentResponse;
import com.example.jobpuzzle.document.dto.DocumentUploadRequest;
import com.example.jobpuzzle.document.dto.ExtractionEditRequest;
import com.example.jobpuzzle.document.dto.ExtractionJobResponse;
import com.example.jobpuzzle.document.dto.ExtractionVersionResponse;
import com.example.jobpuzzle.document.entity.UserDocumentType;
import com.example.jobpuzzle.document.service.DocumentExtractionService;
import com.example.jobpuzzle.document.service.DocumentService;
import com.example.jobpuzzle.global.common.ApiResponse;
import com.example.jobpuzzle.global.common.dto.PageResponse;
import com.example.jobpuzzle.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final DocumentExtractionService documentExtractionService;

    // 파일 자료 등록 (이미지는 여러 장을 한 번에 등록 가능)
    // POST /jobpuzzle/documents (multipart: files, documentType, displayName, keepOriginal)
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<DocumentResponse>> uploadDocument(
            @RequestPart("files") List<MultipartFile> files,
            @RequestParam("documentType") UserDocumentType documentType,
            @RequestParam("displayName") String displayName,
            @RequestParam(value = "keepOriginal", defaultValue = "false") boolean keepOriginal,
            @AuthenticationPrincipal(expression = "user") User user
    ) {
        DocumentUploadRequest request = new DocumentUploadRequest(documentType, files, displayName, keepOriginal);
        DocumentResponse response = documentService.uploadDocument(user.getUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    // 직접 입력 자료 등록 및 최초 DRAFT 버전 생성
    // POST /jobpuzzle/documents/text
    @PostMapping("/documents/text")
    public ResponseEntity<ApiResponse<ExtractionVersionResponse>> registerTextDocument(
            @Valid @RequestBody DirectDocumentRegisterRequest request,
            @AuthenticationPrincipal(expression = "user") User user
    ) {
        ExtractionVersionResponse response =
                documentService.registerTextDocument(user.getUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    // PDF 텍스트 추출 또는 OCR 실행 (비동기) - 완료 결과는 버전 이력 조회로 확인
    // POST /jobpuzzle/documents/{documentId}/extractions
    @PostMapping("/documents/{documentId}/extractions")
    public ResponseEntity<ApiResponse<ExtractionJobResponse>> extractDocumentText(
            @PathVariable Long documentId,
            @AuthenticationPrincipal(expression = "user") User user
    ) {
        ExtractionJobResponse response =
                documentExtractionService.extractDocumentText(user.getUserId(), documentId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(response));
    }

    // 내 자료 목록 조회
    // GET /jobpuzzle/documents?documentType=&page=&size=
    @GetMapping("/documents")
    public ResponseEntity<ApiResponse<PageResponse<DocumentListResponse>>> getDocumentList(
            @RequestParam(required = false) UserDocumentType documentType,
            Pageable pageable,
            @AuthenticationPrincipal(expression = "user") User user
    ) {
        PageResponse<DocumentListResponse> response =
                documentService.getDocumentList(user.getUserId(), documentType, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // 자료 메타와 최신 버전 요약 조회
    // GET /jobpuzzle/documents/{documentId}
    @GetMapping("/documents/{documentId}")
    public ResponseEntity<ApiResponse<DocumentDetailResponse>> getDocumentDetail(
            @PathVariable Long documentId,
            @AuthenticationPrincipal(expression = "user") User user
    ) {
        DocumentDetailResponse response =
                documentService.getDocumentDetail(user.getUserId(), documentId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // 자료 버전 이력 조회
    // GET /jobpuzzle/documents/{documentId}/extractions
    @GetMapping("/documents/{documentId}/extractions")
    public ResponseEntity<ApiResponse<List<ExtractionVersionResponse>>> getDocumentVersions(
            @PathVariable Long documentId,
            @AuthenticationPrincipal(expression = "user") User user
    ) {
        List<ExtractionVersionResponse> response =
                documentExtractionService.getDocumentVersions(user.getUserId(), documentId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // 자료명 변경 (버전 이력·확정 상태와 무관하게 즉시 반영)
    // PATCH /jobpuzzle/documents/{documentId}/name { displayName }
    @PatchMapping("/documents/{documentId}/name")
    public ResponseEntity<ApiResponse<Void>> updateDisplayName(
            @PathVariable Long documentId,
            @Valid @RequestBody DocumentNameUpdateRequest request,
            @AuthenticationPrincipal(expression = "user") User user
    ) {
        documentService.updateDisplayName(user.getUserId(), documentId, request.getDisplayName());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // 자료 논리 삭제 및 신규 분석 대상 제외
    // DELETE /jobpuzzle/documents/{documentId}
    @DeleteMapping("/documents/{documentId}")
    public ResponseEntity<Void> deleteDocument(
            @PathVariable Long documentId,
            @AuthenticationPrincipal(expression = "user") User user
    ) {
        documentService.deleteDocument(user.getUserId(), documentId);
        return ResponseEntity.noContent().build();
    }

    // 특정 추출·수정 버전 조회
    // GET /jobpuzzle/document-extractions/{extractionId}
    @GetMapping("/document-extractions/{extractionId}")
    public ResponseEntity<ApiResponse<ExtractionVersionResponse>> getExtractionVersion(
            @PathVariable Long extractionId,
            @AuthenticationPrincipal(expression = "user") User user
    ) {
        ExtractionVersionResponse response =
                documentExtractionService.getExtractionVersion(user.getUserId(), extractionId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // 수정 내용을 새 DRAFT 버전으로 저장 (기존 버전 본문 UPDATE 금지)
    // POST /jobpuzzle/document-extractions/{baseExtractionId}/versions { content }
    @PostMapping("/document-extractions/{baseExtractionId}/versions")
    public ResponseEntity<ApiResponse<ExtractionVersionResponse>> saveEditedVersion(
            @PathVariable Long baseExtractionId,
            @Valid @RequestBody ExtractionEditRequest request,
            @AuthenticationPrincipal(expression = "user") User user
    ) {
        ExtractionVersionResponse response = documentExtractionService.saveEditedVersion(
                user.getUserId(), baseExtractionId, request
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    // 최신 DRAFT 추출본 개별 확정
    // POST /jobpuzzle/document-extractions/{extractionId}/confirm
    @PostMapping("/document-extractions/{extractionId}/confirm")
    public ResponseEntity<ApiResponse<ExtractionVersionResponse>> confirmExtraction(
            @PathVariable Long extractionId,
            @AuthenticationPrincipal(expression = "user") User user
    ) {
        ExtractionVersionResponse response =
                documentExtractionService.confirmExtraction(user.getUserId(), extractionId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
