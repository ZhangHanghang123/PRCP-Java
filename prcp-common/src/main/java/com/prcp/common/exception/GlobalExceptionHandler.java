package com.prcp.common.exception;

import com.prcp.common.result.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.servlet.http.HttpServletRequest;
import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import java.util.stream.Collectors;

/**
 * 全局异常处理器
 * 对齐 FastAPI 端 HTTPException 处理风格
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常 */
    @ExceptionHandler(BizException.class)
    public R<?> handleBiz(BizException e, HttpServletRequest req) {
        log.warn("[BizException] {} - {}", req.getRequestURI(), e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    /** @Valid @RequestBody 校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<?> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("[Valid] {}", msg);
        return R.badRequest(msg);
    }

    /** @Valid 表单/Query 参数 */
    @ExceptionHandler(BindException.class)
    public R<?> handleBind(BindException e) {
        FieldError fe = e.getBindingResult().getFieldError();
        return R.badRequest(fe != null ? fe.getDefaultMessage() : "参数错误");
    }

    /** @Validated 单参数校验 */
    @ExceptionHandler(ConstraintViolationException.class)
    public R<?> handleConstraint(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        return R.badRequest(msg);
    }

    /** 兜底：未捕获异常 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<?>> handleAll(Exception e, HttpServletRequest req) {
        log.error("[UNHANDLED] {} - {}", req.getRequestURI(), e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(R.fail("服务器内部错误：" + e.getMessage()));
    }
}
