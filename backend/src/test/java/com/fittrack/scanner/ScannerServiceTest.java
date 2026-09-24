package com.fittrack.scanner;
import org.junit.jupiter.api.*; import static org.junit.jupiter.api.Assertions.*;
class ScannerServiceTest { @Test void rejectsSpoofedImage(){assertNull(ScannerService.detect("not an image".getBytes()));} @Test void acceptsPngSignature(){byte[] b={ (byte)137,80,78,71,13,10,26,10};assertEquals("image/png",ScannerService.detect(b));} }
