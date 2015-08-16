package com.nice.czp.htmlsocket.push;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.http.server.HttpServerFilter;
import org.glassfish.grizzly.http.server.HttpServerProbe.Adapter;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nice.czp.htmlsocket.api.IMessage;
import com.nice.czp.htmlsocket.api.IRemoteSubcriber;
import com.nice.czp.htmlsocket.api.ISubscriber;

/**
 * 消息中心,负责管理订阅者和派发消息,为了提升100W级时的广播性能<br>
 * 根据CPU数将map分组,每个核负责一个map的分发
 * 
 * @author coder_czp@126.com-2015年8月8日
 * 
 */
public class MessageCenter extends Adapter {

    private static Logger log = LoggerFactory.getLogger(MessageCenter.class);
    private static final int cpus = Runtime.getRuntime().availableProcessors();
    private List<ThreadMap> maps = new CopyOnWriteArrayList<ThreadMap>();
    private AtomicLong count = new AtomicLong(0);

    public MessageCenter() {
        for (int i = 0; i < cpus; i++) {
            maps.add(new ThreadMap());
        }
    }

    public void send(IMessage msg) {
        if (msg.getTo() != -1) {
            sendTo(msg.getTo(), msg);
        } else if (msg.getSubject() != null) {
            broadcastGroup(msg.getSubject(), msg);
        } else {
            log.info("msg.to and msg.subject is null so push to all");
            broadcastAll(msg);
        }

    }

    /**
     * 发送给指定的订阅者
     * 
     * @param toId
     *            目标
     * @param msg
     */
    public void sendTo(long toId, IMessage msg) {
        for (ThreadMap map : maps) {
            if (map.sendTo(toId, msg))
                return;
        }
        log.error("no ISubscriber'id match:[{}] msg:[{}]", toId, msg);
    }

    /**
     * 广播给subject指定的组
     * 
     * @param subject
     *            组topic
     * @param msg
     *            msg.subject不能为null
     */
    public void broadcastGroup(String subject, IMessage msg) {
        for (ThreadMap map : maps) {
            map.broadcastGroup(subject, msg);
        }
    }

    /***
     * 广播给所有订阅者
     * 
     * @param message
     */
    public void broadcastAll(IMessage message) {
        log.info("push message to all start");
        for (ThreadMap map : maps) {
            map.broadcastAll(message);
        }
        log.info("push message to all finished");
    }

    public void addSubscriber(ISubscriber sub) {
        int index = (int) (count.getAndIncrement() % cpus);
        maps.get(index).put(sub.getId(), sub);

    }

    public boolean removeSubscriber(ISubscriber sub) {
        return removeSubscriber(sub.getId());
    }

    public boolean removeSubscriber(long id) {
        for (ThreadMap map : maps) {
            ISubscriber sub = map.remove(id);
            if (sub == null)
                continue;
            if (sub instanceof IRemoteSubcriber) {
                ((IRemoteSubcriber) sub).doClose();
            }
            return true;
        }
        return false;
    }

    public Collection<ThreadMap> getSubscribers() {
        return Collections.unmodifiableCollection(maps);
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void onRequestCompleteEvent(HttpServerFilter filter, Connection connection, Response response) {
        try {
            Request request = response.getRequest();
            String id = request.getParameter("id");
            String holdConn = request.getParameter("hold");
            if (id != null && !"false".equals(holdConn)) {
                int port = request.getRemotePort();
                String ip = request.getRemoteAddr();
                if (removeSubscriber(Long.valueOf(id))) {
                    log.info("long poll client:[{}:{}] close", ip, port);
                }
            }
        } catch (Exception e) {
            log.error("remove long poll client error", e);
        }
    }
}
