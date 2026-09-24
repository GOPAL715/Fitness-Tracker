package com.fittrack.api;
import org.springframework.http.*; import org.springframework.web.bind.annotation.*; import java.util.*;
@RestControllerAdvice public class GlobalExceptionHandler {
 @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> bad(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("status",400,"error","Bad Request","message",e.getMessage()));}
 @ExceptionHandler(NoSuchElementException.class) ResponseEntity<?> missing(NoSuchElementException e){return ResponseEntity.status(404).body(Map.of("status",404,"error","Not Found","message",e.getMessage()));}
  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class) ResponseEntity<?> methodNotSupported(org.springframework.web.HttpRequestMethodNotSupportedException e){return ResponseEntity.status(404).body(Map.of("status",404,"error","Not Found","message","Resource endpoint not found"));}
 @ExceptionHandler(Exception.class) ResponseEntity<?> other(Exception e){return ResponseEntity.internalServerError().body(Map.of("status",500,"error","Internal Server Error","message","Unexpected error"));}
}
