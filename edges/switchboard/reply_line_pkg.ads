package Reply_Line_Pkg with SPARK_Mode is

   --  This unit locates the boundaries of lines within a fixed-length string,
   --  where a line is a maximal sequence of characters without a line feed.
   --  It defines helper functions to identify the end of a line, the start of
   --  the next line, and whether a line is empty. The unit is purely functional
   --  and contains no state, procedures, or I/O operations.

   Max_Text : constant := 1048576;

   function Is_Line_Feed (C : Character) return Boolean is
     (C = Character'Val (10));

   function Line_End (L : String; I : Positive) return Positive is
     ((if I > L'Last then I else (if Is_Line_Feed (L (I)) then I else Line_End (L, I + 1))))
     with
       Pre => L'First = 1 and then L'Last >= 0 and then L'Last <= Max_Text and then I <= L'Last + 1,
       Post => Line_End'Result in I .. L'Last + 1
         and then (for all J in I .. Line_End'Result - 1 => not Is_Line_Feed (L (J)))
         and then (if Line_End'Result <= L'Last then Is_Line_Feed (L (Line_End'Result))),
       Subprogram_Variant => (Increases => I);

   function Next_Line (L : String; I : Positive) return Positive is
     (Line_End (L, I) + 1)
     with
       Pre => L'First = 1 and then L'Last >= 0 and then L'Last <= Max_Text and then I <= L'Last + 1,
       Post => Next_Line'Result > I and then Next_Line'Result <= L'Last + 2;

   function Is_Empty_Line (L : String; I : Positive) return Boolean is
     (Line_End (L, I) = I)
     with
       Pre => L'First = 1 and then L'Last >= 0 and then L'Last <= Max_Text and then I <= L'Last + 1,
       Post => Is_Empty_Line'Result = (I > L'Last or else Is_Line_Feed (L (I)));

end Reply_Line_Pkg;
