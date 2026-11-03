package com.vale.helperbot.path.moves;

import java.util.List;

/** Every move the search knows, asked in this order about each cell (see {@link Move}). */
public final class Moves {
	public static final List<Move> ALL = List.of(
			new WalkMove(),
			new SwimMove(),
			new DigMove(),
			new BuildMoves.Bridge(),
			new BuildMoves.Pillar(),
			new DigDownMove());

	private Moves() {
	}
}
