package com.fittrack.api;
import org.springframework.http.*; import org.springframework.web.bind.annotation.*; import java.util.*;
@RestControllerAdvice public class GlobalExceptionHandler {
 @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> bad(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("status",400,"error","Bad Request","message",e.getMessage()));}
 @ExceptionHandler(NoSuchElementException.class) ResponseEntity<?> missing(NoSuchElementException e){return ResponseEntity.status(404).body(Map.of("status",404,"error","Not Found","message",e.getMessage()));}
  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class) ResponseEntity<?> methodNotSupported(org.springframework.web.HttpRequestMethodNotSupportedException e){return ResponseEntity.status(404).body(Map.of("status",404,"error","Not Found","message","Resource endpoint not found"));}
  @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class) ResponseEntity<?> invalid(org.springframework.web.bind.MethodArgumentNotValidException e){String message=e.getBindingResult().getFieldErrors().stream().map(f->f.getField()+": "+f.getDefaultMessage()).findFirst().orElse("Invalid request");return ResponseEntity.badRequest().body(Map.of("status",400,"error","Bad Request","message",message));}
 @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class) ResponseEntity<?> unreadable(org.springframework.http.converter.HttpMessageNotReadableException e){return ResponseEntity.badRequest().body(Map.of("status",400,"error","Bad Request","message","Invalid request body"));}

  @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class) ResponseEntity<?> denied(org.springframework.security.access.AccessDeniedException e){return ResponseEntity.status(403).body(Map.of("status",403,"error","Forbidden","message",e.getMessage()==null?"Access denied":e.getMessage()));}
  // An unparseable path variable or query parameter (for example an unparseable date) is a client
  // error, so it must surface as a structured 400 rather than an opaque 500.
  @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class) ResponseEntity<?> typeMismatch(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException e){return ResponseEntity.badRequest().body(Map.of("status",400,"error","Bad Request","message",e.getName()+" has an invalid value"));}
  @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class) ResponseEntity<?> status(org.springframework.web.server.ResponseStatusException e){int code=e.getStatusCode().value();String message=e.getReason()==null?"Request failed":e.getReason();return ResponseEntity.status(code).body(Map.of("status",code,"error",code==409?"Conflict":code==404?"Not Found":code==401?"Unauthorized":code==403?"Forbidden":code==429?"Too Many Requests":"Bad Request","message",message));}
  @ExceptionHandler(com.fittrack.ai.AiUsageService.QuotaExceededException.class) ResponseEntity<?> quota(com.fittrack.ai.AiUsageService.QuotaExceededException e){return ResponseEntity.status(429).body(Map.of("status",429,"error","Too Many Requests","message","AI request limit exceeded"));}
  @ExceptionHandler(com.fittrack.ai.AiProviderException.class) ResponseEntity<?> provider(com.fittrack.ai.AiProviderException e){return ResponseEntity.status(502).body(Map.of("status",502,"error","Bad Gateway","message","AI provider unavailable"));}

 @ExceptionHandler(Exception.class) ResponseEntity<?> other(Exception e){return ResponseEntity.internalServerError().body(Map.of("status",500,"error","Internal Server Error","message","Unexpected error"));}
}
