package com.seatly.venue;

import com.seatly.common.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A physical hall. Owns its seating chart, which is stable across events.
 */
@Entity
@Table(name = "venue")
public class Venue extends BaseEntity {

	@Column(name = "name", nullable = false, length = 160)
	private String name;

	@Column(name = "city", nullable = false, length = 80)
	private String city;

	/**
	 * Cascaded because a section has no meaning outside its venue: the two are
	 * created, saved and deleted together. Bounded in size (a hall has a handful
	 * of sections), which is what makes mapping the collection reasonable at all.
	 */
	@OneToMany(mappedBy = "venue", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("displayOrder asc")
	private List<SeatSection> sections = new ArrayList<>();

	/** Required by JPA. */
	protected Venue() {
	}

	public Venue(String name, String city) {
		this.name = name;
		this.city = city;
	}

	/**
	 * Keeps both ends of the association in step. Setting only one side is the
	 * classic bidirectional-mapping bug: the in-memory object graph looks right,
	 * but the foreign key column is never written.
	 */
	public SeatSection addSection(String name, int displayOrder) {
		SeatSection section = new SeatSection(this, name, displayOrder);
		sections.add(section);
		return section;
	}

	public String getName() {
		return name;
	}

	public String getCity() {
		return city;
	}

	public List<SeatSection> getSections() {
		return Collections.unmodifiableList(sections);
	}

}
