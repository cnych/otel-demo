package com.youdianzhishi.orderservice.controller;

import com.youdianzhishi.orderservice.OrderserviceApplication;
import com.youdianzhishi.orderservice.model.Order;
import com.youdianzhishi.orderservice.model.OrderDto;
import com.youdianzhishi.orderservice.model.User;
import com.youdianzhishi.orderservice.repository.OrderRepository;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private static final Logger logger = LoggerFactory.getLogger(OrderserviceApplication.class);

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private WebClient webClient;

    @Autowired
    private Tracer tracer;

    @GetMapping
    public ResponseEntity<List<OrderDto>> getAllOrders(HttpServletRequest request) {
        // 从请求属性里面获取 Span
        Span span = (Span) request.getAttribute("currentSpan");
        Context context = Context.current().with(span);

        try {
            // 从拦截器中获取用户信息
            User user = (User) request.getAttribute("user");

            span.setAttribute("user_id", user.getId());

            // 新建一个 DB 查询的 span
            Span dbSpan = tracer.spanBuilder("DB findByUserIdOrderByOrderDateDesc").setParent(context).startSpan();

            // 要根据 orderDate 倒序排列
            List<Order> orders = orderRepository.findByUserIdOrderByOrderDateDesc(user.getId());
            dbSpan.setAttribute("order_count", orders.size());
            dbSpan.addEvent("DB query complete");
            dbSpan.end();

            // 将Order转换为OrderDto
            List<OrderDto> orderDtos = orders.stream().map(order -> {
                try {
                    return order.toOrderDto(webClient, tracer, context);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).collect(Collectors.toList());

            span.addEvent("order convert to dto complete");

            return new ResponseEntity<>(orderDtos, HttpStatus.OK);
        } catch (Exception e) {
            span.recordException(e).setStatus(StatusCode.ERROR, e.getMessage());
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        } finally {
            span.end();
        }
    }

    @PostMapping
    public ResponseEntity<Map<String, Long>> createOrder(@RequestBody Order order, HttpServletRequest request) {
        // 从请求属性里面获取 Span
        Span span = (Span) request.getAttribute("currentSpan");
        Context context = Context.current().with(span);

        try {
            // 从拦截器中获取用户信息
            User user = (User) request.getAttribute("user");

            // 设置订单的用户id
            order.setUserId(user.getId());

            // 设置默认状态为已下单
            order.setStatus(Order.PENDING);
            Date orderDate = new Date();
            order.setOrderDate(orderDate);

            span.setAttribute("user_id", user.getId());
            span.setAttribute("order_date", orderDate.toString());

            Span dbSpan = tracer.spanBuilder("DB save").setParent(context).startSpan();
            // 保存订单
            Order savedOrder = orderRepository.save(order);
            dbSpan.addEvent("DB save complete").setStatus(StatusCode.OK);
            dbSpan.end();

            // 只需要返回订单ID即可
            Map<String, Long> response = new HashMap<>();
            response.put("id", savedOrder.getId());

            span.addEvent("order create complete").setStatus(StatusCode.OK);

            return new ResponseEntity<>(response, HttpStatus.CREATED);
        } catch (Exception e) {
            span.recordException(e).setStatus(StatusCode.ERROR, e.getMessage());
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        } finally {
            span.end();
        }
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderDto> getOrderById(@PathVariable Long orderId, HttpServletRequest request) {
        // 从请求属性里面获取 Span
        Span span = (Span) request.getAttribute("currentSpan");
        Context context = Context.current().with(span);

        try {
            span.setAttribute("order_id", orderId);

            // 创建一个 DB 查询的 span
            Span dbSpan = tracer.spanBuilder("DB findById").setParent(context).startSpan();
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order == null) {
                dbSpan.addEvent("order not found").setStatus(StatusCode.ERROR);
                dbSpan.end();
                return new ResponseEntity<>(HttpStatus.NOT_FOUND);
            }
            dbSpan.end();

            span.addEvent("DB query complete");

            // 根据订单中的书籍id，批量调用图书服务获取书籍信息
            // 需要得到书籍列表、总数、总价
            // 创建 OrderDto 对象并填充数据
            OrderDto orderDto = order.toOrderDto(webClient, tracer, context);
            span.addEvent("order convert to dto complete").setStatus(StatusCode.OK);
            return new ResponseEntity<>(orderDto, HttpStatus.OK);
        } catch (Exception e) {
            logger.error("Fetch books info error: {}", e.getMessage());
            span.recordException(e).setStatus(StatusCode.ERROR, e.getMessage());
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        } finally {
            span.end();
        }
    }

    @PutMapping("/{orderId}/cancel")
    public ResponseEntity<String> cancelOrder(@PathVariable Long orderId, HttpServletRequest request) {
        // 从拦截器中获取用户信息
        User user = (User) request.getAttribute("user");

        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        if (order.getUserId() != user.getId()) {
            return new ResponseEntity<>("该订单不属于当前用户", HttpStatus.FORBIDDEN);
        }

        if (order.getStatus() != Order.PENDING) {
            return new ResponseEntity<>("只有未发货的订单才能取消", HttpStatus.BAD_REQUEST);
        }

        // 设置订单状态为已取消
        order.setStatus(Order.CANCELLED);
        orderRepository.save(order);
        return new ResponseEntity<>("Ok", HttpStatus.OK);
    }

    @DeleteMapping("/{orderId}")
    public ResponseEntity<String> deleteOrder(@PathVariable Long orderId, HttpServletRequest request) {
        // 从拦截器中获取用户信息
        User user = (User) request.getAttribute("user");

        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        if (order.getUserId() != user.getId()) {
            return new ResponseEntity<>("该订单不属于当前用户", HttpStatus.FORBIDDEN);
        }

        if (order.getStatus() != Order.CANCELLED) {
            return new ResponseEntity<>("只有已取消的订单才能删除", HttpStatus.BAD_REQUEST);
        }

        orderRepository.deleteById(orderId);
        return new ResponseEntity<>("Ok", HttpStatus.OK);
    }

    @PostMapping("/{orderId}/status/{status}")
    public ResponseEntity<String> updateStatus(@PathVariable Long orderId, @PathVariable int status,
            HttpServletRequest request) {
        
        // 从请求属性里面获取 Span
        Span span = (Span) request.getAttribute("currentSpan");
        Context context = Context.current().with(span);

        try {
            // 从拦截器中获取用户信息
            User user = (User) request.getAttribute("user");
            span.setAttribute("user_id", user.getId());

            // 新建一个 DB 查询的 span
            Span dbSpan = tracer.spanBuilder("DB findById").setParent(context).startSpan();
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order == null) {
                dbSpan.addEvent("order not found").setStatus(StatusCode.ERROR);
                dbSpan.end();
                return new ResponseEntity<>(HttpStatus.NOT_FOUND);
            }
            if (order.getUserId() != user.getId()) {
                dbSpan.addEvent("order user is not current user").setStatus(StatusCode.ERROR);
                dbSpan.end();
                return new ResponseEntity<>("该订单不属于当前用户", HttpStatus.FORBIDDEN);
            }
            dbSpan.end();
            order.setStatus(status);
            
            orderRepository.save(order);
            span.setAttribute("order_id", order.getId()).addEvent("order status update complete").setStatus(StatusCode.OK);

            return new ResponseEntity<>("Ok", HttpStatus.OK);
        } catch (Exception e) {
            logger.error("Update order status error: {}", e.getMessage());
            span.recordException(e).setStatus(StatusCode.ERROR, e.getMessage());
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        } finally {
            span.end();
        }
    }
}
