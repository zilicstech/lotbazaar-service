package com.lotbazaar.commerce;

import com.lotbazaar.lots.LotService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Make an Offer: buyer offers, seller accepts / counters / rejects; acceptance reserves the lot for payment. */
@Service
@Transactional
public class OfferService {

	public record OfferView(UUID id, UUID lotId, String lotCode, String lotTitle, BigDecimal lotTotalMrp,
			BigDecimal lotPriceAmount, BigDecimal amount, BigDecimal counterAmount, String status, String note,
			UUID orderId, String buyerName, String buyerBusiness, String buyerCity, Instant createdAt,
			Instant updatedAt, String orderStatus) {
	}

	private static final String OFFER_SELECT = """
			select f.id, f.lot_id, l.code as lot_code, l.title as lot_title, l.total_mrp as lot_total_mrp,
			       l.price_amount as lot_price_amount, f.amount, f.counter_amount, f.status, f.note, f.order_id,
			       u.name as buyer_name, u.business_name as buyer_business, u.city as buyer_city, f.created_at,
			       f.updated_at, o.status as order_status
			from offer f join lot l on l.id = f.lot_id join app_user u on u.id = f.buyer_user_id
			left join sales_order o on o.id = f.order_id""";

	private final JdbcClient jdbc;

	private final LotService lots;

	private final OrderService orders;

	OfferService(JdbcClient jdbc, LotService lots, OrderService orders) {
		this.jdbc = jdbc;
		this.lots = lots;
		this.orders = orders;
	}

	public OfferView make(UUID tenantId, UUID buyerId, UUID lotId, BigDecimal amount, String note) {
		orders.requireApproved(buyerId);
		var lot = lots.lot(lotId);
		if (!"LISTED".equals(lot.status()) || !lot.acceptOffers()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "This lot is not taking offers");
		}
		if (amount == null || amount.compareTo(lot.offerFloorAmount()) < 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Offers on this lot start at ₹" + lot.offerFloorAmount().toPlainString());
		}
		if (amount.compareTo(lot.priceAmount()) >= 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Your offer is at or above the Buy Now price; use Buy Now instead");
		}
		var id = UUID.randomUUID();
		try {
			jdbc.sql("insert into offer(id, tenant_id, lot_id, buyer_user_id, amount, note) values (?,?,?,?,?,?)")
				.params(id, tenantId, lotId, buyerId, amount, note == null || note.isBlank() ? null : note.trim())
				.update();
		}
		catch (DuplicateKeyException e) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "You already have an open offer on this lot");
		}
		return offer(id);
	}

	/** Seller accepts the buyer's offer: the lot is held for 24 hours and a payment-pending order is opened. */
	public OfferView accept(UUID tenantId, UUID offerId) {
		var o = offer(offerId);
		if (!"OPEN".equals(o.status())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Only open offers can be accepted");
		}
		return close(tenantId, offerId, o.lotId(), buyerOf(offerId), o.amount(), "OPEN");
	}

	public OfferView counter(UUID offerId, BigDecimal amount) {
		var o = offer(offerId);
		if (amount == null || amount.compareTo(o.amount()) <= 0 || amount.compareTo(o.lotPriceAmount()) >= 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"A counter must be above the buyer's offer and below the Buy Now price");
		}
		if (jdbc.sql("""
				update offer set status = 'COUNTERED', counter_amount = ?, updated_at = now()
				where id = ? and status = 'OPEN'""").params(amount, offerId).update() != 1) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Only open offers can be countered");
		}
		return offer(offerId);
	}

	public OfferView reject(UUID offerId) {
		if (jdbc.sql("""
				update offer set status = 'REJECTED', updated_at = now()
				where id = ? and status in ('OPEN', 'COUNTERED')""").param(offerId).update() != 1) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "This offer is already closed");
		}
		return offer(offerId);
	}

	/** Buyer takes the seller's counter price. */
	public OfferView acceptCounter(UUID tenantId, UUID buyerId, UUID offerId) {
		orders.requireApproved(buyerId);
		var o = offer(offerId);
		if (!buyerId.equals(buyerOf(offerId))) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Offer not found");
		}
		if (!"COUNTERED".equals(o.status())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "There is no counter offer to accept");
		}
		return close(tenantId, offerId, o.lotId(), buyerId, o.counterAmount(), "COUNTERED");
	}

	/** Buyer withdraws an offer or declines the seller's counter. */
	public OfferView withdraw(UUID buyerId, UUID offerId) {
		if (!buyerId.equals(buyerOf(offerId))) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Offer not found");
		}
		return reject(offerId);
	}

	@Transactional(readOnly = true)
	public List<OfferView> offers() {
		return jdbc.sql(OFFER_SELECT + " order by f.status = 'OPEN' desc, f.updated_at desc").query(OfferView.class).list();
	}

	@Transactional(readOnly = true)
	public List<OfferView> offersOfBuyer(UUID buyerId) {
		return jdbc.sql(OFFER_SELECT + " where f.buyer_user_id = ? order by f.updated_at desc")
			.param(buyerId)
			.query(OfferView.class)
			.list();
	}

	private OfferView close(UUID tenantId, UUID offerId, UUID lotId, UUID buyerId, BigDecimal price, String from) {
		if (!lots.reserve(lotId)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "This lot is no longer available");
		}
		var orderId = orders.createPending(tenantId, buyerId, lotId, price, "OFFER", OrderService.OFFER_HOLD);
		if (jdbc.sql("""
				update offer set status = 'ACCEPTED', order_id = ?, updated_at = now() where id = ? and status = ?""")
			.params(orderId, offerId, from)
			.update() != 1) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "This offer changed; refresh and try again");
		}
		jdbc.sql("""
				update offer set status = 'EXPIRED', updated_at = now()
				where lot_id = ? and id <> ? and status in ('OPEN', 'COUNTERED')""").params(lotId, offerId).update();
		return offer(offerId);
	}

	private UUID buyerOf(UUID offerId) {
		return jdbc.sql("select buyer_user_id from offer where id = ?").param(offerId).query(UUID.class).single();
	}

	private OfferView offer(UUID id) {
		return jdbc.sql(OFFER_SELECT + " where f.id = ?")
			.param(id)
			.query(OfferView.class)
			.optional()
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Offer not found"));
	}

}
