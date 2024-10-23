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

import static org.eclipse.store.demo.bookstore.util.ValidationUtils.requirePositive;

import java.util.Objects;

public class InventoryItem
{
	private final Book book  ;
	private final int  amount;

	public InventoryItem(
		final Book book  ,
		final int  amount
	)
	{
		super();
		this.book   = Objects.requireNonNull(book, () -> "Book cannot be null");
		this.amount = requirePositive(amount, () -> "Amount must be greater than zero");
	}
	

	/**
	 * Get the book of this inventory item
	 *
	 * @return the book
	 */
	public Book book()
	{
		return this.book;
	}

	/**
	 * Get the amount of this inventory item
	 *
	 * @return the amount
	 */
	public int amount()
	{
		return this.amount;
	}

}
