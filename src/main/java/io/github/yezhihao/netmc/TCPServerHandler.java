package io.github.yezhihao.netmc;

import io.github.yezhihao.netmc.core.HandlerInterceptor;
import io.github.yezhihao.netmc.core.HandlerMapping;
import io.github.yezhihao.netmc.core.handler.Handler;
import io.github.yezhihao.netmc.core.model.Message;
import io.github.yezhihao.netmc.session.Session;
import io.github.yezhihao.netmc.session.SessionManager;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author yezhihao
 * home https://gitee.com/yezhihao/jt808-server
 */
@ChannelHandler.Sharable
public class TCPServerHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(TCPServerHandler.class.getSimpleName());

    private HandlerMapping handlerMapping;

    private HandlerInterceptor interceptor;

    private SessionManager sessionManager;

    public TCPServerHandler(HandlerMapping handlerMapping, HandlerInterceptor interceptor, SessionManager sessionManager) {
        this.handlerMapping = handlerMapping;
        this.interceptor = interceptor;
        this.sessionManager = sessionManager;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof Message))
            return;
        Message request = (Message) msg;
        Message response;
        Channel channel = ctx.channel();
        Session session = channel.attr(Session.KEY).get();
        long time = session.access();

        try {
            Handler handler = handlerMapping.getHandler(request.getMessageType());
            if (handler != null) {
                if (!interceptor.beforeHandle(request, session))
                    return;

                response = handler.invoke(request, session);
                if (handler.returnVoid) {
                    response = interceptor.successful(request, session);
                } else {
                    interceptor.afterHandle(request, response, session);
                }
            } else {
                response = interceptor.notSupported(request, session);
            }
        } catch (Exception e) {
            log.warn(String.valueOf(request), e);
            response = interceptor.exceptional(request, session, e);
        }
        time = System.currentTimeMillis() - time;
        if (time > 200)
            log.info("=========消息ID{},处理耗时{}ms,", request.getHeader(), time);
        if (response != null)
            ctx.writeAndFlush(response);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        Session session = sessionManager.newSession(channel);
        channel.attr(Session.KEY).set(session);
        log.info(">>>>>终端连接{}", session);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        Session session = ctx.channel().attr(Session.KEY).get();
        session.invalidate();
        log.info("<<<<<断开连接{}", session);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable e) {
        Session session = ctx.channel().attr(Session.KEY).get();
        log.warn("<<<<<消息处理异常" + session, e);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;
            IdleState state = event.state();
            if (state == IdleState.READER_IDLE || state == IdleState.WRITER_IDLE) {
                Session session = ctx.channel().attr(Session.KEY).get();
                log.warn("<<<<<终端主动断开连接{}", session);
                ctx.close();
            }
        }
    }
}