package Space_Words_Pkg with SPARK_Mode is
   -- Space_Words_Pkg splits a single line into its words. The edge of the switchboard reads one line and then
   -- asks Word_Count how many words it holds, and asks Word_First and Word_Last for the first and last index of
   -- each word, so that the edge only calls this unit and writes no word-cutting loop of its own.

   Max_Line : constant := 256;

   function All_Spaces (L : String) return Boolean is
     (for all J in L'Range => L (J) = ' ')
     with Pre => L'First = 1 and then L'Last >= 0 and then L'Last <= Max_Line;

   function Next_Non_Space (L : String; I : Positive) return Positive is
     ((if I > L'Last or else L (I) /= ' ' then I else Next_Non_Space (L, I + 1)))
     with Pre => L'First = 1 and then L'Last >= 0 and then L'Last <= Max_Line and then I <= L'Last + 1,
          Post => Next_Non_Space'Result in I .. L'Last + 1
                  and then (if Next_Non_Space'Result <= L'Last then L (Next_Non_Space'Result) /= ' ')
                  and then (for all J in I .. Next_Non_Space'Result - 1 => L (J) = ' '),
          Subprogram_Variant => (Increases => I);

   function Next_Space (L : String; I : Positive) return Positive is
     ((if I > L'Last or else L (I) = ' ' then I else Next_Space (L, I + 1)))
     with Pre => L'First = 1 and then L'Last >= 0 and then L'Last <= Max_Line and then I <= L'Last + 1,
          Post => Next_Space'Result in I .. L'Last + 1
                  and then (if Next_Space'Result <= L'Last then L (Next_Space'Result) = ' ')
                  and then (for all J in I .. Next_Space'Result - 1 => L (J) /= ' ')
                  and then (if I <= L'Last and then L (I) /= ' ' then Next_Space'Result > I),
          Subprogram_Variant => (Increases => I);

   function Words_From (L : String; I : Positive) return Natural is
     ((if Next_Non_Space (L, I) > L'Last then 0 else 1 + Words_From (L, Next_Space (L, Next_Non_Space (L, I)))))
     with Pre => L'First = 1 and then L'Last >= 0 and then L'Last <= Max_Line and then I <= L'Last + 1,
          Post => Words_From'Result <= L'Last - I + 1,
          Subprogram_Variant => (Increases => I);

   function Word_Count (L : String) return Natural is
     (Words_From (L, 1))
     with Pre => L'First = 1 and then L'Last >= 0 and then L'Last <= Max_Line,
          Post => (if All_Spaces (L) then Word_Count'Result = 0) and then Word_Count'Result <= L'Last;

   function Word_First (L : String; K : Positive) return Positive is
     ((if K = 1 then Next_Non_Space (L, 1) else Next_Non_Space (L, Next_Space (L, Word_First (L, K - 1)))))
     with Pre => L'First = 1 and then L'Last >= 0 and then L'Last <= Max_Line and then K <= Word_Count (L),
          Post => Word_First'Result in 1 .. L'Last
                  and then L (Word_First'Result) /= ' '
                  and then (if Word_First'Result > 1 then L (Word_First'Result - 1) = ' ')
                  and then Words_From (L, Word_First'Result) = Word_Count (L) - K + 1,
          Subprogram_Variant => (Decreases => K);

   function Word_Last (L : String; K : Positive) return Positive is
     (Next_Space (L, Word_First (L, K)) - 1)
     with Pre => L'First = 1 and then L'Last >= 0 and then L'Last <= Max_Line and then K <= Word_Count (L),
          Post => Word_First (L, K) <= Word_Last'Result
                  and then Word_Last'Result <= L'Last
                  and then (for all J in Word_First (L, K) .. Word_Last'Result => L (J) /= ' ')
                  and then (if Word_Last'Result < L'Last then L (Word_Last'Result + 1) = ' ')
                  and then (if K < Word_Count (L) then Word_First (L, K + 1) > Word_Last'Result);

end Space_Words_Pkg;
