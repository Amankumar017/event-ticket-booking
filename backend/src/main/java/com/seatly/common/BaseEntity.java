package com.seatly.common;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.Hibernate;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;

/**
 * Identity and audit timestamps shared by every persistent entity.
 * <p>
 * {@code createdAt} and {@code updatedAt} are owned by the database -- column
 * defaults set them on insert, and the {@code set_updated_at} trigger from V1
 * maintains them on update. The {@code @Generated} annotation tells Hibernate to
 * read the values back rather than assume it knows them, so an in-memory entity
 * never disagrees with the row it came from. Doing this in the database rather
 * than in Java means the timestamps stay correct even when a migration, a
 * script, or another service does the writing.
 */
@MappedSuperclass
public abstract class BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Generated(event = EventType.INSERT)
	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	@Generated(event = {EventType.INSERT, EventType.UPDATE})
	@Column(name = "updated_at", insertable = false, updatable = false)
	private Instant updatedAt;

	public Long getId() {
		return id;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	/**
	 * Equality is by database identity, and only once the entity actually has
	 * one. Two unsaved entities are never equal, which is the honest answer --
	 * they are two distinct objects that may yet become two distinct rows.
	 * <p>
	 * {@link Hibernate#getClass} is used instead of {@code getClass()} because a
	 * lazily loaded association hands back a proxy subclass; comparing raw
	 * classes would report a proxy and its own entity as different objects.
	 */
	@Override
	public final boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) {
			return false;
		}
		BaseEntity that = (BaseEntity) other;
		return id != null && id.equals(that.getId());
	}

	/**
	 * Constant per entity type on purpose. A hash code must not change while the
	 * object sits in a collection, and an entity's id does change -- from null to
	 * a real value -- the moment it is persisted. Hashing on the id would strand
	 * entities in the wrong bucket of any {@code HashSet} they were added to
	 * before the flush.
	 */
	@Override
	public final int hashCode() {
		return Hibernate.getClass(this).hashCode();
	}

}
