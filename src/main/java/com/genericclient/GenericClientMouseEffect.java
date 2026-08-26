package com.genericclient;

public enum GenericClientMouseEffect
{
	OFF("Off"),
	TRAIL("Trail"),
	PATH("Path");

	private final String label;

	GenericClientMouseEffect(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
