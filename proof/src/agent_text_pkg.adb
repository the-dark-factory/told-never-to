package body Agent_Text_Pkg with SPARK_Mode is

   procedure Make_Plain (Source : String; Target : out String) is
   begin
      Target := (others => ' ');
      for I in Source'Range loop
         pragma Loop_Invariant (Target'First = 1 and then Target'Last = Source'Last);
         pragma Loop_Invariant
           (for all J in Source'First .. I - 1 => Target (J) = Plain_Char (Source, J));
         Target (I) := Plain_Char (Source, I);
      end loop;
   end Make_Plain;

end Agent_Text_Pkg;