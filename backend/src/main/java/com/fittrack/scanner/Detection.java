package com.fittrack.scanner; public record Detection(String name,double estimatedGrams,double confidence){} record ScanResponse(String status,java.util.List<Detection> items){}
