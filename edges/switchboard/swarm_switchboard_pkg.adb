package body Swarm_Switchboard_Pkg with SPARK_Mode => On is

   procedure Record_Admitted
     (G      : Call_Graph;
      Cap    : Cap_Value;
      Before : Round_Counts;
      Caller : Agent_Id;
      Callee : Agent_Id;
      After  : out Round_Counts)
   is
   begin
      pragma Unreferenced (G);
      --  Decide = Admit implies Before (Caller, Callee) < Cap, so the increment
      --  stays within Cap and cannot overflow Natural.
      pragma Assert (Before (Caller, Callee) < Cap);
      After := Before;
      After (Caller, Callee) := Before (Caller, Callee) + 1;
   end Record_Admitted;

   procedure New_Round (C : out Round_Counts) is
   begin
      C := (others => (others => 0));
   end New_Round;

end Swarm_Switchboard_Pkg;