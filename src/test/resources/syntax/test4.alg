begin
comment swap procedure;
	integer n, m;
	
	procedure swap(a,b); integer a, b;
	begin
		integer c;
		c := a;
		a := b;
		b := c
	end;
		
	n := 2;  m := 3;
	outinteger (1,n); outinteger (1,m); outstring (1,"\n");
	swap(n, m);
	outinteger (1,n); outinteger (1,m); outstring (1,"\n")
	
end