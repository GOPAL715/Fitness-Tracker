package com.fittrack.storage;
import java.io.*;
public interface PrivateObjectStorage {StoredObject put(String userId,InputStream input,String contentType)throws IOException;void delete(String userId,String key)throws IOException;record StoredObject(String key,String contentType,long size){}}
