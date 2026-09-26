package com.fittrack.api;

import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.stereotype.Component;
import org.slf4j.MDC;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

@Component
public class RequestCorrelationFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String supplied=request.getHeader("X-Request-Id"); String id;
        try { id=supplied==null?UUID.randomUUID().toString():UUID.fromString(supplied).toString(); } catch(IllegalArgumentException e){id=UUID.randomUUID().toString();}
        MDC.put("request_id",id); response.setHeader("X-Request-Id",id);
        try{chain.doFilter(request,response);} finally{MDC.remove("request_id");}
    }
}
