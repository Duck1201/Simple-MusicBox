package com.duck.simplemusicbox.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class DurationMatchTest {
	@Test
	void picksClosestDurationAmongTopResults() {
		// clipe estendido (4:41) em primeiro, áudio oficial (3:49) em segundo
		assertEquals(1, DurationMatch.bestIndex(List.of(281_000L, 229_500L, 231_000L), 229_000));
	}

	@Test
	void keepsSearchOrderOnTie() {
		assertEquals(0, DurationMatch.bestIndex(List.of(230_000L, 228_000L), 229_000));
	}

	@Test
	void noExpectedDurationTakesFirst() {
		assertEquals(0, DurationMatch.bestIndex(List.of(999_000L, 200_000L), 0));
	}

	@Test
	void rejectsWhenNothingIsCloseEnough() {
		// tolerância de 10% (22,9 s) para 229 s: 3:00 e 4:41 ficam de fora
		assertEquals(-1, DurationMatch.bestIndex(List.of(180_000L, 281_000L), 229_000));
		assertEquals(-1, DurationMatch.bestIndex(List.of(), 229_000));
	}

	@Test
	void shortSongsHaveMinimumTolerance() {
		// 60 s: 10% seria 6 s, mas o mínimo é 10 s
		assertEquals(0, DurationMatch.bestIndex(List.of(69_000L), 60_000));
	}

	@Test
	void onlyTopCandidatesAreConsidered() {
		List<Long> results = List.of(1L, 1L, 1L, 1L, 1L, 229_000L);
		assertEquals(-1, DurationMatch.bestIndex(results, 229_000));
	}
}
