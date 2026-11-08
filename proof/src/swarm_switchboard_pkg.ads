package Swarm_Switchboard_Pkg with SPARK_Mode => On is
   --  This switchboard decides whether one call from one agent to another may pass, based on a fixed call graph and a per-edge cap.
   --  The caller reads the graph and the cap, asks Decide for each call, and calls Record_Admitted when the answer is Admit.
   --  The content of a call is not a parameter of anything in this unit, so the decision never depends on what a call carries.

   Max_Agents : constant := 32;

   subtype Agent_Id is Positive range 1 .. Max_Agents;

   type Call_Graph is array (Agent_Id, Agent_Id) of Boolean;

   subtype Cap_Value is Positive;

   type Round_Counts is array (Agent_Id, Agent_Id) of Natural;

   type Decision is (Admit, Refuse_Off_Graph, Refuse_Edge_Full);

   function No_Self_Calls (G : Call_Graph) return Boolean is
     ((for all A in Agent_Id => G (A, A) = False));

   function Row_Within_Cap (C : Round_Counts; Cap : Cap_Value; Caller : Agent_Id) return Boolean is
     ((for all Callee in Agent_Id => C (Caller, Callee) <= Cap));

   function Counts_Within_Cap (C : Round_Counts; Cap : Cap_Value) return Boolean is
     ((for all Caller in Agent_Id => Row_Within_Cap (C, Cap, Caller)));

   function Row_Is_Empty (G : Call_Graph; Caller : Agent_Id) return Boolean is
     ((for all Callee in Agent_Id => G (Caller, Callee) = False));

   function Graph_Is_Empty (G : Call_Graph) return Boolean is
     ((for all Caller in Agent_Id => Row_Is_Empty (G, Caller)));

   function Decide (G : Call_Graph; Cap : Cap_Value; C : Round_Counts; Caller : Agent_Id; Callee : Agent_Id) return Decision is
     (if not G (Caller, Callee) then Refuse_Off_Graph else (if C (Caller, Callee) >= Cap then Refuse_Edge_Full else Admit))
      with Pre => No_Self_Calls (G) and then Counts_Within_Cap (C, Cap),
           Post => ((if G (Caller, Callee) = False then Decide'Result = Refuse_Off_Graph) and then
                    (if G (Caller, Callee) and then C (Caller, Callee) >= Cap then Decide'Result = Refuse_Edge_Full) and then
                    (if G (Caller, Callee) and then C (Caller, Callee) < Cap then Decide'Result = Admit) and then
                    (if Decide'Result = Admit then G (Caller, Callee)) and then
                    (if G (Caller, Callee) = False then Decide'Result /= Admit) and then
                    (if Graph_Is_Empty (G) then Decide'Result = Refuse_Off_Graph));

   function Others_Unchanged (Before : Round_Counts; After : Round_Counts; Caller : Agent_Id; Callee : Agent_Id) return Boolean is
     ((for all A in Agent_Id => (for all B in Agent_Id => (if A /= Caller or else B /= Callee then Before (A, B) = After (A, B)))));

   procedure Record_Admitted (G : Call_Graph; Cap : Cap_Value; Before : Round_Counts; Caller : Agent_Id; Callee : Agent_Id; After : out Round_Counts)
      with Pre => No_Self_Calls (G) and then Counts_Within_Cap (Before, Cap) and then Decide (G, Cap, Before, Caller, Callee) = Admit,
           Post => After (Caller, Callee) = Before (Caller, Callee) + 1 and then Others_Unchanged (Before, After, Caller, Callee) and then Counts_Within_Cap (After, Cap);

   function Row_All_Zero (C : Round_Counts; Caller : Agent_Id) return Boolean is
     ((for all Callee in Agent_Id => C (Caller, Callee) = 0));

   function All_Zero (C : Round_Counts) return Boolean is
     ((for all Caller in Agent_Id => Row_All_Zero (C, Caller)));

   procedure New_Round (C : out Round_Counts)
      with Post => All_Zero (C);

end Swarm_Switchboard_Pkg;
