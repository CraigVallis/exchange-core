package org.openpredict.exchange.core.orderbook;

import lombok.Getter;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.openpredict.exchange.beans.L2MarketData;
import org.openpredict.exchange.beans.Order;
import org.openpredict.exchange.beans.StateHash;
import org.openpredict.exchange.beans.SymbolType;
import org.openpredict.exchange.beans.cmd.CommandResultCode;
import org.openpredict.exchange.beans.cmd.OrderCommand;
import org.openpredict.exchange.beans.cmd.OrderCommandType;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public interface IOrderBook extends WriteBytesMarshallable, StateHash {

    /**
     * Process new order.
     * Depending on price specified (whether the order is marketable),
     * order will be matched to existing opposite GTC orders from the order book.
     * In case of remaining volume (order was not matched completely):
     * IOC - reject it as partially filled.
     * GTC - place as a new limit order into th order book.
     *
     * @param cmd - order to match/place
     * @return command code (success, or rejection reason)
     */
    CommandResultCode newOrder(OrderCommand cmd);

    /**
     * Cancel order
     * <p>
     * orderId - order Id
     *
     * @return false if order was not found, otherwise always true
     */
    boolean cancelOrder(OrderCommand cmd);

    /**
     * Move an order
     * <p>
     * orderId  - order Id
     * newPrice - new price (if 0 or same - order will not moved)
     * newSize  - new size (if higher than current size or 0 - order will not downsized)
     *
     * @return false if order was not found, otherwise always true
     */
    CommandResultCode moveOrder(OrderCommand cmd);


    int getOrdersNum();

    Order getOrderById(long orderId);

    List<IOrdersBucket> getAllAskBuckets();

    List<IOrdersBucket> getAllBidBuckets();

    /**
     * Request best ask price.
     *
     * @return best ask price, or Long.MAX_VALUE if there are no asks
     */
    long getBestAsk();

    /**
     * Request best bid price.
     *
     * @return best ask price, or 0 if there are no bids
     */
    long getBestBid();

    // testing only - validateInternalState without changing state
    void validateInternalState();

    /**
     * @return actual implementation
     */
    OrderBookImplType getImplementationType();


    /**
     * Search for all orders for specified user.<br/>
     * Slow, because order book do not maintain uid->order index.<br/>
     * Produces garbage.<br/>
     * Orders must be processed before doing any other mutable call.<br/>
     *
     * @param uid user id
     * @return list of orders
     */
    List<Order> findUserOrders(long uid);

    /**
     * State hash for order books is implementation-agnostic
     * Look {@link org.openpredict.exchange.core.orderbook.IOrderBook#validateInternalState} for complete state validation for de-serialized objects
     */
    @Override
    default int stateHash() {
        return hashCode();
    }

    // TODO to default?
    static int hash(final IOrdersBucket[] askBuckets, final IOrdersBucket[] bidBuckets, final SymbolType symbolType) {
        final int a = Arrays.hashCode(askBuckets);
        final int b = Arrays.hashCode(bidBuckets);
        return Objects.hash(a, b, symbolType.getCode());
    }

    // TODO to default?
    static boolean equals(IOrderBook me, Object o) {
        if (o == me) return true;
        if (o == null) return false;
        if (!(o instanceof IOrderBook)) return false;
        IOrderBook other = (IOrderBook) o;
        return new EqualsBuilder()
                // TODO compare symbol?
                .append(me.getAllAskBuckets(), other.getAllAskBuckets())
                .append(me.getAllBidBuckets(), other.getAllBidBuckets())
                .isEquals();

    }

    default void printFullOrderBook() {
        getAllAskBuckets().forEach(a -> System.out.println(String.format("ASK %s", a.dumpToSingleLine())));
        getAllBidBuckets().forEach(b -> System.out.println(String.format("BID %s", b.dumpToSingleLine())));
    }


    /**
     * @param size max size for each part (ask, bid)
     * @return
     */

    /**
     * Obtain current L2 Market Data snapshot
     *
     * @param size max size for each part (ask, bid), if negative - all records returned
     * @return L2 Market Data snapshot
     */
    default L2MarketData getL2MarketDataSnapshot(int size) {
        int asksSize = getTotalAskBuckets();
        int bidsSize = getTotalBidBuckets();
        if (size >= 0) {
            // limit size
            asksSize = Math.min(asksSize, size);
            bidsSize = Math.min(bidsSize, size);
        }
        L2MarketData data = new L2MarketData(asksSize, bidsSize);
        fillAsks(asksSize, data);
        fillBids(bidsSize, data);
        return data;
    }

    /**
     * Request to publish L2 market data into outgoing disruptor message
     *
     * @param data - pre-allocated object from ring buffer
     */
    default void publishL2MarketDataSnapshot(L2MarketData data) {
        int size = L2MarketData.L2_SIZE;
        fillAsks(size, data);
        fillBids(size, data);
    }

    void fillAsks(final int size, L2MarketData data);

    void fillBids(final int size, L2MarketData data);

    int getTotalAskBuckets();

    int getTotalBidBuckets();


    static CommandResultCode processCommand(final IOrderBook orderBook, final OrderCommand cmd) {

        final OrderCommandType commandType = cmd.command;

        if (commandType == OrderCommandType.MOVE_ORDER) {

            return orderBook.moveOrder(cmd);

        } else if (commandType == OrderCommandType.CANCEL_ORDER) {

            boolean isCancelled = orderBook.cancelOrder(cmd);
            return isCancelled ? CommandResultCode.SUCCESS : CommandResultCode.MATCHING_UNKNOWN_ORDER_ID;

        } else if (commandType == OrderCommandType.PLACE_ORDER) {

            return (cmd.resultCode == CommandResultCode.VALID_FOR_MATCHING_ENGINE)
                    ? orderBook.newOrder(cmd)
                    : cmd.resultCode; // no change

        } else if (commandType == OrderCommandType.ORDER_BOOK_REQUEST) {

            //log.debug("ORDER_BOOK_REQUEST {}", cmd.size);
            cmd.marketData = orderBook.getL2MarketDataSnapshot((int) cmd.size);
            return CommandResultCode.SUCCESS;

        } else {
            //log.warn("unsupported command {}", cmd.command);
            return CommandResultCode.MATCHING_UNSUPPORTED_COMMAND;
        }

    }


    static IOrderBook create(OrderBookImplType type, final SymbolType symbolType) {
        switch (type) {
            case NAIVE:
                return new OrderBookNaiveImpl(symbolType);
            case FAST:
                return new OrderBookFastImpl(OrderBookFastImpl.DEFAULT_HOT_WIDTH, symbolType);
            default:
                throw new IllegalArgumentException();
        }
    }

    static IOrderBook create(BytesIn bytes) {
        switch (OrderBookImplType.of(bytes.readByte())) {
            case NAIVE:
                return new OrderBookNaiveImpl(bytes);
            case FAST:
                return new OrderBookFastImpl(bytes);
            default:
                throw new IllegalArgumentException();
        }
    }


    @Getter
    enum OrderBookImplType {
        NAIVE(0),
        FAST(1);

        private byte code;

        OrderBookImplType(int code) {
            this.code = (byte) code;
        }

        public static OrderBookImplType of(byte code) {
            switch (code) {
                case 0:
                    return NAIVE;
                case 1:
                    return FAST;
                default:
                    throw new IllegalArgumentException("unknown OrderBookImplType:" + code);
            }
        }
    }


}
