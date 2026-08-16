package jp.tonbiattack.debuglab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CouponSelectorTest {

    @Test
    void presentCoupon_doesNotLookupDefaultCoupon() {
        CountingCouponRepository repository = new CountingCouponRepository("DEFAULT");
        CouponSelector selector = new CouponSelector(repository);

        String actual = selector.selectCoupon(Optional.of("SPRING10"));

        assertEquals("SPRING10", actual);
        assertEquals(0, repository.findCalls(),
                "クーポンが存在する場合はデフォルト取得を実行すべきではない");
    }

    @Test
    void emptyCoupon_looksUpDefaultCouponOnce() {
        CountingCouponRepository repository = new CountingCouponRepository("DEFAULT");
        CouponSelector selector = new CouponSelector(repository);

        String actual = selector.selectCoupon(Optional.empty());

        assertEquals("DEFAULT", actual);
        assertEquals(1, repository.findCalls());
    }

    @Test
    void orElseGet_doesNotInvokeSupplierWhenValueIsPresent() {
        AtomicInteger supplierCalls = new AtomicInteger();

        String actual = Optional.of("SPRING10")
                .orElseGet(() -> {
                    supplierCalls.incrementAndGet();
                    return "DEFAULT";
                });

        assertEquals("SPRING10", actual);
        assertEquals(0, supplierCalls.get());
    }

    private static final class CountingCouponRepository implements CouponRepository {

        private final String defaultCoupon;
        private int findCalls;

        private CountingCouponRepository(String defaultCoupon) {
            this.defaultCoupon = defaultCoupon;
        }

        @Override
        public String findDefaultCoupon() {
            findCalls++;
            System.out.printf("findDefaultCoupon called: count=%d, value=%s%n",
                    findCalls, defaultCoupon);
            return defaultCoupon;
        }

        private int findCalls() {
            return findCalls;
        }
    }
}
