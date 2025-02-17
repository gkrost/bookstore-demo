
package org.eclipse.store.demo.bookstore.data;

/*-
 * #%L
 * EclipseStore BookStore Demo
 * %%
 * Copyright (C) 2023 MicroStream Software
 * %%
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 * 
 * SPDX-License-Identifier: EPL-2.0
 * #L%
 */

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.summingInt;
import static java.util.stream.Collectors.toList;
import static org.eclipse.store.demo.bookstore.util.CollectionUtils.ensureParallelStream;
import static org.eclipse.store.demo.bookstore.util.CollectionUtils.maxKey;
import static org.eclipse.store.demo.bookstore.util.CollectionUtils.summingMonetaryAmount;
import static org.eclipse.store.demo.bookstore.util.LazyUtils.clearIfStored;
import static org.javamoney.moneta.function.MonetaryFunctions.summarizingMonetary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import javax.money.MonetaryAmount;

import org.eclipse.serializer.concurrency.StripeLockScope;
import org.eclipse.serializer.persistence.types.PersistenceStoring;
import org.eclipse.serializer.reference.Lazy;
import org.eclipse.store.demo.bookstore.BookStoreDemo;
import org.eclipse.store.storage.embedded.types.EmbeddedStorageManager;

import com.google.common.collect.Range;

/**
 * All purchases made by all customers in all stores.
 * <p>
 * This type is used to read and write the {@link Purchase}s and statistics thereof.
 * <p>
 * All operations on this type are thread safe.
 *
 * @see Data#purchases()
 * @see StripeLockScope
 */
public class Purchases extends StripeLockScope
{
	/**
	 * This class hold all purchases made in a specific year.
	 * <p>
	 * Note that this class doesn't need to handle concurrency in any way,
	 * since it is only used by the Purchases implementation which handles thread safety.
	 */
	private static class YearlyPurchases
	{
		/*
		 * Multiple maps holding references to the purchases, for a faster lookup.
		 */
		final Map<Shop,     Lazy<List<Purchase>>> shopToPurchases     = new HashMap<>(128);
		final Map<Employee, Lazy<List<Purchase>>> employeeToPurchases = new HashMap<>(512);
		final Map<Customer, Lazy<List<Purchase>>> customerToPurchases = new HashMap<>(1024);

		YearlyPurchases()
		{
			super();
		}

		/**
		 * Adds a purchase to all collections used by this class.
		 *
		 * @param purchase the purchase to add
		 */
		YearlyPurchases add(
			final Purchase           purchase,
			final PersistenceStoring persister
		)
		{
			final List<Object> changedObjects = new ArrayList<>();
			addToMap(this.shopToPurchases,     purchase.shop(),     purchase, changedObjects);
			addToMap(this.employeeToPurchases, purchase.employee(), purchase, changedObjects);
			addToMap(this.customerToPurchases, purchase.customer(), purchase, changedObjects);
			if(persister != null && changedObjects.size() > 0)
			{
				persister.storeAll(changedObjects);
			}
			return this;
		}

		/**
		 * Adds a purchase to a map with a list as values.
		 * If no list is present for the given key, it will be created.
		 *
		 * @param <K> the key type
		 * @param map the collection
		 * @param key the key
		 * @param purchase the purchase to add
		 */
		private static <K> void addToMap(
			final Map<K, Lazy<List<Purchase>>> map,
			final K key,
			final Purchase purchase,
			final List<Object> changedObjects
		)
		{
			Lazy<List<Purchase>> lazy = map.get(key);
			if(lazy == null)
			{
				final ArrayList<Purchase> list = new ArrayList<>(64);
				list.add(purchase);
				lazy = Lazy.Reference(list);
				map.put(key, lazy);
				changedObjects.add(map);
			}
			else
			{
				final List<Purchase> list = lazy.get();
				list.add(purchase);
				changedObjects.add(list);
			}
		}

		/**
		 * Clears all {@link Lazy} references used by this type
		 */
		void clear()
		{
			clearMap(this.shopToPurchases);
			clearMap(this.employeeToPurchases);
			clearMap(this.customerToPurchases);
		}

		/**
		 * Clears all {@link Lazy} references in the given map.
		 *
		 * @param <K> the key type
		 * @param map the map to clear
		 */
		private static <K> void clearMap(
			final Map<K, Lazy<List<Purchase>>> map
		)
		{
			map.values().forEach(lazy ->
				clearIfStored(lazy).ifPresent(List::clear)
			);
		}

		/**
		 * @param shop the shop to filter by
		 * @return parallel stream with purchases made in a specific shop
		 */
		Stream<Purchase> byShop(
			final Shop shop
		)
		{
			return ensureParallelStream(
				Lazy.get(this.shopToPurchases.get(shop))
			);
		}

		/**
		 * @param shopSelector the predicate to filter by
		 * @return parallel stream with purchases made in specific shops
		 */
		Stream<Purchase> byShops(
			final Predicate<Shop> shopSelector
		)
		{
			return this.shopToPurchases.entrySet().parallelStream()
				.filter(e -> shopSelector.test(e.getKey()))
				.flatMap(e -> ensureParallelStream(Lazy.get(e.getValue())));
		}

		/**
		 * @param employee the employee to filter by
		 * @return parallel stream with purchases made by a specific employee
		 */
		Stream<Purchase> byEmployee(
			final Employee employee
		)
		{
			return ensureParallelStream(
				Lazy.get(this.employeeToPurchases.get(employee))
			);
		}

		/**
		 * @param customer the customer to filter by
		 * @return parallel stream with purchases made by a specific customer
		 */
		Stream<Purchase> byCustomer(
			final Customer customer
		)
		{
			return ensureParallelStream(
				Lazy.get(this.customerToPurchases.get(customer))
			);
		}

	}
	
	
	/**
	 * Map with {@link YearlyPurchases}, indexed by the year, of course.
	 */
	private final Map<Integer, Lazy<YearlyPurchases>> yearlyPurchases = new ConcurrentHashMap<>(32);

	public Purchases()
	{
		super();
	}
	
	/**
	 * This method is used exclusively by the {@link RandomDataGenerator}
	 * and it's not published by the {@link Purchases} interface.
	 */
	Set<Customer> init(
		final int year,
		final List<Purchase>     purchases,
		final PersistenceStoring persister
	)
	{
		return this.write(year, () ->
		{
			final YearlyPurchases yearlyPurchases = new YearlyPurchases();
			purchases.forEach(p -> yearlyPurchases.add(p, null));

			final Lazy<YearlyPurchases> lazy = Lazy.Reference(yearlyPurchases);
			this.yearlyPurchases.put(year, lazy);

			persister.store(this.yearlyPurchases);

			final Set<Customer> customers = new HashSet<>(yearlyPurchases.customerToPurchases.keySet());

			yearlyPurchases.clear();
			lazy.clear();

			return customers;
		});
	}
	
	/**
	 * Adds a new purchase and stores it with the {@link BookStoreDemo}'s {@link EmbeddedStorageManager}.
	 * <p>
	 * This is a synonym for:<pre>this.add(purchase, BookStoreDemo.getInstance().storageManager())</pre>
	 *
	 * @param purchase the new purchase
	 */
	public void add(final Purchase purchase)
	{
		this.add(purchase, BookStoreDemo.getInstance().storageManager());
	}

	/**
	 * Adds a new purchase and stores it with the given persister.
	 *
	 * @param purchase the new purchase
	 * @param persister the persister to store it with
	 * @see #add(Purchase)
	 */
	public void add(
		final Purchase           purchase ,
		final PersistenceStoring persister
	)
	{
		final Integer year = purchase.timestamp().getYear();
		this.write(year, () ->
		{
			final Lazy<YearlyPurchases> lazy = this.yearlyPurchases.get(year);
			if(lazy != null)
			{
				lazy.get().add(purchase, persister);
			}
			else
			{
				this.write(0, () -> {
					this.yearlyPurchases.put(
						year,
						Lazy.Reference(
							new YearlyPurchases().add(purchase, null)
						)
					);
					persister.store(this.yearlyPurchases);
				});
			}
		});
	}

	/**
	 * Gets the range of all years in which purchases were made.
	 *
	 * @return all years with revenue
	 */
	public Range<Integer> years()
	{
		return this.read(0, () -> {
			final IntSummaryStatistics summary = this.yearlyPurchases.keySet().stream()
				.mapToInt(Integer::intValue)
				.summaryStatistics();
			return Range.closed(summary.getMin(), summary.getMax());
		});
	}

	/**
	 * Clears all {@link Lazy} references regarding all purchases.
	 * This frees the used memory but you do not lose the persisted data. It is loaded again on demand.
	 *
	 * @see #clear(int)
	 */
	public void clear()
	{
		final List<Integer> years = this.read(0, () ->
			new ArrayList<>(this.yearlyPurchases.keySet())
		);
		years.forEach(this::clear);
	}

	/**
	 * Clears all {@link Lazy} references regarding purchases of a specific year.
	 * This frees the used memory but you do not lose the persisted data. It is loaded again on demand.
	 *
	 * @param year the year to clear
	 * @see #clear()
	 */
	public void clear(
		final int year
	)
	{
		this.write(year, () ->
			clearIfStored(this.yearlyPurchases.get(year))
				.ifPresent(YearlyPurchases::clear)
		);
	}

	/**
	 * Executes a function with a pre-filtered {@link Stream} of {@link Purchase}s and returns the computed value.
	 *
	 * @param <T> the return type
	 * @param year year to filter by
	 * @param streamFunction computing function
	 * @return the computed result
	 */
	public <T> T computeByYear(
		final int                           year          ,
		final Function<Stream<Purchase>, T> streamFunction
	)
	{
		return this.read(year, () ->
		{
			final YearlyPurchases yearlyPurchases = Lazy.get(this.yearlyPurchases.get(year));
			return streamFunction.apply(
				yearlyPurchases == null
					? Stream.empty()
					: yearlyPurchases.shopToPurchases.values().parallelStream()
						.map(l -> l.get())
						.flatMap(List::stream)
			);
		});
	}

	/**
	 * Executes a function with a pre-filtered {@link Stream} of {@link Purchase}s and returns the computed value.
	 *
	 * @param <T> the return type
	 * @param shop shop to filter by
	 * @param year year to filter by
	 * @param streamFunction computing function
	 * @return the computed result
	 */
	public <T> T computeByShopAndYear(
		final Shop                          shop          ,
		final int                           year          ,
		final Function<Stream<Purchase>, T> streamFunction
	)
	{
		return this.read(year, () ->
		{
			final YearlyPurchases yearlyPurchases = Lazy.get(this.yearlyPurchases.get(year));
			return streamFunction.apply(
				yearlyPurchases == null
					? Stream.empty()
					: yearlyPurchases.byShop(shop)
			);
		});
	}

	/**
	 * Executes a function with a pre-filtered {@link Stream} of {@link Purchase}s and returns the computed value.
	 *
	 * @param <T> the return type
	 * @param shopSelector predicate for shops to filter by
	 * @param year year to filter by
	 * @param streamFunction computing function
	 * @return the computed result
	 */
	public <T> T computeByShopsAndYear(
		final Predicate<Shop>               shopSelector  ,
		final int                           year          ,
		final Function<Stream<Purchase>, T> streamFunction
	)
	{
		return this.read(year, () ->
		{
			final YearlyPurchases yearlyPurchases = Lazy.get(this.yearlyPurchases.get(year));
			return streamFunction.apply(
				yearlyPurchases == null
					? Stream.empty()
					: yearlyPurchases.byShops(shopSelector)
			);
		});
	}

	/**
	 * Executes a function with a pre-filtered {@link Stream} of {@link Purchase}s and returns the computed value.
	 *
	 * @param <T> the return type
	 * @param employee employee to filter by
	 * @param year year to filter by
	 * @param streamFunction computing function
	 * @return the computed result
	 */
	public <T> T computeByEmployeeAndYear(
		final Employee                      employee      ,
		final int                           year          ,
		final Function<Stream<Purchase>, T> streamFunction
	)
	{
		return this.read(year, () ->
		{
			final YearlyPurchases yearlyPurchases = Lazy.get(this.yearlyPurchases.get(year));
			return streamFunction.apply(
				yearlyPurchases == null
					? Stream.empty()
					: yearlyPurchases.byEmployee(employee)
			);
		});
	}

	/**
	 * Executes a function with a pre-filtered {@link Stream} of {@link Purchase}s and returns the computed value.
	 *
	 * @param <T> the return type
	 * @param customer customer to filter by
	 * @param year year to filter by
	 * @param streamFunction computing function
	 * @return the computed result
	 */
	public <T> T computeByCustomerAndYear(
		final Customer                      customer      ,
		final int                           year          ,
		final Function<Stream<Purchase>, T> streamFunction
	)
	{
		return this.read(year, () ->
		{
			final YearlyPurchases yearlyPurchases = Lazy.get(this.yearlyPurchases.get(year));
			return streamFunction.apply(
				yearlyPurchases == null
					? Stream.empty()
					: yearlyPurchases.byCustomer(customer)
			);
		});
	}

	/**
	 * Computes the best selling books for a specific year.
	 *
	 * @param year the year to filter by
	 * @return list of best selling books
	 */
	public List<BookSales> bestSellerList(final int year)
	{
		return this.computeByYear(
			year,
			this::bestSellerList
		);
	}

	/**
	 * Computes the best selling books for a specific year and country.
	 *
	 * @param year the year to filter by
	 * @param country the country to filter by
	 * @return list of best selling books
	 */
	public List<BookSales> bestSellerList(
		final int     year   ,
		final Country country
	)
	{
		return this.computeByShopsAndYear(
			shopInCountryPredicate(country),
			year,
			this::bestSellerList
		);
	}
	
	private List<BookSales> bestSellerList(final Stream<Purchase> purchases)
	{
		return purchases
			.flatMap(Purchase::items)
			.collect(
				groupingBy(
					PurchaseItem::book,
					summingInt(PurchaseItem::amount)
				)
			)
			.entrySet()
			.stream()
			.map(e -> new BookSales(e.getKey(), e.getValue()))
			.sorted()
			.collect(toList());
	}

	/**
	 * Counts all purchases which were made by customers in foreign countries.
	 *
	 * @param year the year to filter by
	 * @return the amount of computed purchases
	 */
	public long countPurchasesOfForeigners(final int year)
	{
		return this.computePurchasesOfForeigners(
			year,
			purchases -> purchases.count()
		);
	}

	/**
	 * Computes all purchases which were made by customers in foreign cities.
	 *
	 * @param year the year to filter by
	 * @return a list of purchases
	 */
	public List<Purchase> purchasesOfForeigners(final int year)
	{
		return this.computePurchasesOfForeigners(
			year,
			purchases -> purchases.collect(toList())
		);
	}
	
	private <T> T computePurchasesOfForeigners(
		final int                            year          ,
		final Function <Stream<Purchase>, T> streamFunction
	)
	{
		return this.computeByYear(
			year,
			purchases -> streamFunction.apply(
				purchases.filter(
					purchaseOfForeignerPredicate()
				)
			)
		);
	}

	/**
	 * Counts all purchases which were made by customers in foreign cities.
	 *
	 * @param year the year to filter by
	 * @param country the country to filter by
	 * @return the amount of computed purchases
	 */
	public long countPurchasesOfForeigners(
		final int     year   ,
		final Country country
	)
	{
		return this.computePurchasesOfForeigners(
			year,
			country,
			purchases -> purchases.count()
		);
	}

	/**
	 * Computes all purchases which were made by customers in foreign cities.
	 *
	 * @param year the year to filter by
	 * @param country the country to filter by
	 * @return a list of purchases
	 */
	public List<Purchase> purchasesOfForeigners(
		final int     year   ,
		final Country country
	)
	{
		return this.computePurchasesOfForeigners(
			year,
			country,
			purchases -> purchases.collect(toList())
		);
	}

	private <T> T computePurchasesOfForeigners(
		final int                            year          ,
		final Country                        country       ,
		final Function <Stream<Purchase>, T> streamFunction
	)
	{
		return this.computeByShopsAndYear(
			shopInCountryPredicate(country),
			year,
			purchases -> streamFunction.apply(
				purchases.filter(
					purchaseOfForeignerPredicate()
				)
			)
		);
	}
	
	private static Predicate<Shop> shopInCountryPredicate(final Country country)
	{
		return shop -> shop.address().city().state().country() == country;
	}

	private static Predicate<? super Purchase> purchaseOfForeignerPredicate()
	{
		return p -> p.customer().address().city() != p.shop().address().city();
	}

	/**
	 * Computes the complete revenue of a specific shop in a whole year.
	 *
	 * @param shop the shop to filter by
	 * @param year the year to filter by
	 * @return complete revenue
	 */
	public MonetaryAmount revenueOfShopInYear(
		final Shop shop,
		final int  year
	)
	{
		return this.computeByShopAndYear(
			shop,
			year,
			purchases -> purchases
				.map(Purchase::total)
				.collect(summarizingMonetary(BookStoreDemo.CURRENCY_UNIT))
				.getSum()
		);
	}

	/**
	 * Computes the worldwide best performing employee in a specific year.
	 *
	 * @param year the year to filter by
	 * @return the employee which made the most revenue
	 */
	public Employee employeeOfTheYear(final int year)
	{
		return this.computeByYear(
			year,
			bestPerformingEmployeeFunction()
		);
	}

	/**
	 * Computes the best performing employee in a specific year.
	 *
	 * @param year the year to filter by
	 * @param country the country to filter by
	 * @return the employee which made the most revenue
	 */
	public Employee employeeOfTheYear(
		final int     year   ,
		final Country country
	)
	{
		return this.computeByShopsAndYear(
			shopInCountryPredicate(country) ,
			year,
			bestPerformingEmployeeFunction()
		);
	}

	private static Function<Stream<Purchase>, Employee> bestPerformingEmployeeFunction()
	{
		return purchases -> maxKey(
			purchases.collect(
				groupingBy(
					Purchase::employee,
					summingMonetaryAmount(
						BookStoreDemo.CURRENCY_UNIT,
						Purchase::total
					)
				)
			)
		);
	}

}
