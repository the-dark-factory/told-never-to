package Flat_Number_Pkg with SPARK_Mode => On is
   --  Purpose: Determine whether a given String is a valid house number.
   --  Usage: Call Number_Ok with the String value to check; it returns True if the
   --  String is either an unsigned decimal (1-9 digits) or a signed decimal (- followed
   --  by 1-9 digits).
   function Number_Ok (V : String) return Boolean is
     ((V'Length in 1 .. 9 and then (for all I in V'Range => V (I) in '0' .. '9')) or else (V'Length in 2 .. 10 and then V (V'First) = '-' and then (for all I in V'First + 1 .. V'Last => V (I) in '0' .. '9')))
     with Post => Number_Ok'Result = ((V'Length in 1 .. 9 and then (for all I in V'Range => V (I) in '0' .. '9')) or else (V'Length in 2 .. 10 and then V (V'First) = '-' and then (for all I in V'First + 1 .. V'Last => V (I) in '0' .. '9')));
end Flat_Number_Pkg;
